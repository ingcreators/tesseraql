package io.tesseraql.cli;

import io.tesseraql.cli.mcp.McpCommand;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.core.TesseraqlVersion;
import io.tesseraql.runtime.DataSources;
import io.tesseraql.runtime.TesseraqlRuntime;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * TesseraQL command-line interface (design ch. 17). The short command alias is {@code tql}.
 */
@Command(name = "tesseraql", mixinStandardHelpOptions = true, versionProvider = TesseraqlCli.VersionProvider.class, description = "SQL-first hypermedia and integration framework.", subcommands = {
        TesseraqlCli.ServeCommand.class,
        TesseraqlCli.RoutesCommand.class,
        NewCommand.class,
        ScaffoldCommand.class,
        LintCommand.class,
        TestCommand.class,
        CoverageCommand.class,
        GenerateCommand.class,
        SchemaCommand.class,
        GovernanceCommand.class,
        MigrateCommand.class,
        IdentitySchemaCommand.class,
        PackageCommand.class,
        VerifyCommand.class,
        ModulesCommand.class,
        McpCommand.class
})
public final class TesseraqlCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** Reports the framework version from the single source ({@link TesseraqlVersion}). */
    static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"TesseraQL " + TesseraqlVersion.current()};
        }
    }

    public static void main(String[] args) {
        // Honor HTTP_PROXY/HTTPS_PROXY/NO_PROXY (the container/CI standard the JDK ignores) before
        // any outbound work, so the resolver and runtime clients reach the network behind a proxy.
        ProxyEnvironment.bridgeFromEnvironment();
        // Passive, opt-out, non-blocking "a newer release is available" nudge (Phase 38 Tier 1).
        UpdateNotifier.run(System.err);
        int exitCode = new CommandLine(new TesseraqlCli()).execute(args);
        System.exit(exitCode);
    }

    /** {@code tesseraql serve --app <dir>}: starts the runtime and serves until interrupted. */
    @Command(name = "serve", description = "Start the runtime and serve the app over HTTP.")
    static final class ServeCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Option(names = {"--port"}, description = "Override the configured HTTP port.")
        Integer port;

        @Option(names = {"--modules"}, description = "Directory of optional plugin module jars "
                + "(e.g. the pdf/excel file-format codecs) to load onto the runtime classpath.")
        File modules;

        @Option(names = {
                "--embedded-db"}, arity = "0..1", paramLabel = "<data-dir>", fallbackValue = "", description = "Run with an embedded PostgreSQL (no external "
                        + "database). Pass a directory to persist data across restarts; omit it for "
                        + "an ephemeral run.")
        String embeddedDb;

        @Override
        public Integer call() throws InterruptedException {
            // Load any opt-in plugin modules (file-format codecs, drivers, ...) so route compilation
            // and the runtime discover them via the ServiceLoader SPIs (which use this classloader).
            // The declared tesseraql.modules set is resolved into work/modules (lock-verified) and
            // composes with an explicit --modules directory.
            List<File> moduleDirs = new ArrayList<>();
            AppConfig config = new ManifestLoader().load(app).config();
            new ModulesInstaller().install(app, config, false).ifPresent(result -> {
                moduleDirs.add(result.cacheDir().toFile());
                System.out.println("Resolved " + result.artifacts().size()
                        + " tesseraql.modules artifact(s).");
            });
            if (modules != null) {
                moduleDirs.add(modules);
            }
            Thread.currentThread().setContextClassLoader(CliModules.classLoaderOver(moduleDirs,
                    Thread.currentThread().getContextClassLoader()));

            // Optionally start an embedded PostgreSQL and point the runtime's main datasource at it,
            // so the app runs with no external database. An empty value (the option given without a
            // directory) is an ephemeral run; a directory persists data across restarts.
            DataSources.MainDatasourceOverride dbOverride = null;
            EmbeddedPostgresSupport.Handle embedded = null;
            if (embeddedDb != null) {
                Path dataDir = embeddedDb.isEmpty() ? null : Path.of(embeddedDb);
                embedded = EmbeddedPostgresSupport.start(dataDir, false);
                dbOverride = embedded.override();
                System.out.println("Embedded PostgreSQL started"
                        + (dataDir == null ? " (ephemeral)." : " at " + dataDir + "."));
            }

            TesseraqlRuntime runtime = port != null
                    ? TesseraqlRuntime.start(app, port, dbOverride)
                    : TesseraqlRuntime.start(app, dbOverride);
            EmbeddedPostgresSupport.Handle embeddedToClose = embedded;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                runtime.close();
                // Stop the embedded postgres only after the runtime released its connections.
                if (embeddedToClose != null) {
                    embeddedToClose.close();
                }
            }));
            System.out.println(
                    "TesseraQL serving on port " + runtime.port() + ". Press Ctrl+C to stop.");
            Thread.currentThread().join();
            return 0;
        }
    }

    /** {@code tesseraql routes --app <dir>}: lists the routes discovered in the app. */
    @Command(name = "routes", description = "List the routes discovered in the app.")
    static final class RoutesCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Override
        public Integer call() {
            AppManifest manifest = new ManifestLoader().load(app);
            for (RouteFile route : manifest.routes()) {
                System.out.printf("%-6s %-30s %s%n",
                        route.httpMethod(), route.urlPath(), route.definition().id());
            }
            return 0;
        }
    }
}
