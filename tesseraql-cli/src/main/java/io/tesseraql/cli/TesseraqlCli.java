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

        @Option(names = {"--env"}, paramLabel = "<profile>", description = "Environment "
                + "profile: merges config/env/<profile>.yml between the base config and "
                + "the Studio overlay (also TESSERAQL_ENV).")
        String envProfile;

        @Option(names = {
                "--log-format"}, paramLabel = "<text|json>", description = "Log line format (default text; json for structured logs).")
        String logFormat;

        @Option(names = {
                "--log-level"}, paramLabel = "<level>", description = "Log threshold: trace|debug|info|warn|error (default info).")
        String logLevel;

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

        @Option(names = {"--embedded-db-port"}, paramLabel = "<port>", description = "Bind the "
                + "embedded PostgreSQL to a fixed TCP port (default: a random free port chosen at "
                + "startup). Use it to connect a local client (e.g. psql) at a stable address. "
                + "Listens on localhost only.")
        Integer embeddedDbPort;

        @Override
        public Integer call() throws InterruptedException {
            if (envProfile != null) {
                System.setProperty("tesseraql.env", envProfile);
            }
            // The structured log provider reads these per line (roadmap Phase 45).
            if (logFormat != null) {
                System.setProperty("tesseraql.logging.format", logFormat);
            }
            if (logLevel != null) {
                System.setProperty("tesseraql.logging.level", logLevel);
            }
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
                embedded = EmbeddedPostgresSupport.start(dataDir, embeddedDbPort, false);
                dbOverride = embedded.override();
                System.out.println("Embedded PostgreSQL started"
                        + (dataDir == null ? " (ephemeral)." : " at " + dataDir + "."));
                // Surface the (otherwise random) port so a local client can attach. Trust auth on
                // loopback only — the URL already carries user=postgres; the database is postgres.
                System.out.printf("  Connect a local client on port %d: %s "
                        + "(no password; localhost only).%n", embedded.port(), embedded.jdbcUrl());
            } else if (embeddedDbPort != null) {
                System.err.println("--embedded-db-port is ignored without --embedded-db.");
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
