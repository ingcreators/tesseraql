package io.tesseraql.cli;

import io.tesseraql.cli.mcp.McpCommand;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.core.TesseraqlVersion;
import io.tesseraql.runtime.DataSources;
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
        TesseraqlCli.DevCommand.class,
        HostCommand.class,
        TesseraqlCli.RoutesCommand.class,
        NewCommand.class,
        ScaffoldCommand.class,
        LintCommand.class,
        TokenCommand.class,
        TestCommand.class,
        CoverageCommand.class,
        GenerateCommand.class,
        SchemaCommand.class,
        SymbolsCommand.class,
        ReleaseDiffCommand.class,
        GovernanceCommand.class,
        AdmissionCommand.class,
        MigrateCommand.class,
        JobCommand.class,
        IdentitySchemaCommand.class,
        PackageCommand.class,
        VerifyCommand.class,
        ModulesCommand.class,
        EmbeddedDbCommand.class,
        DuckDbCommand.class,
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
        int exitCode = commandLine().execute(args);
        System.exit(exitCode);
    }

    /**
     * The CLI's picocli front-end: every subcommand plus the shared exception shaping (an
     * unreachable database is a one-line operator message, not a stack trace).
     */
    static CommandLine commandLine() {
        return new CommandLine(new TesseraqlCli())
                .setExecutionExceptionHandler(new UnreachableDatabaseHandler());
    }

    /**
     * {@code tesseraql dev}: runs the development stack over the gateway — every application the
     * stack holds, one origin, one sign-in — until interrupted (docs/cli-surface.md decision 4).
     *
     * <p>It replaces {@code serve}, whose gateway-less single-application shape was the second
     * deployment topology decision 12 removed: development runs the shape production runs.
     * {@code --stack} is discovered one level up when omitted (decision 9), so
     * {@code cd work/orders && tesseraql dev} works with nothing named; {@code --port} is the
     * gateway's front door, and a declared {@code server.port} stays the application's internal
     * port (decision 4a).
     */
    @Command(name = "dev", description = "Run the development stack over the gateway until interrupted.")
    static final class DevCommand implements Callable<Integer> {

        @Option(names = {"--stack"}, paramLabel = "<dir>", description = "Directory holding the"
                + " applications to run: an install root (catalog.json) or a folder of"
                + " application homes. Discovered one level up from the working directory when"
                + " omitted.")
        Path stack;

        @Option(names = {"--app-name"}, paramLabel = "<name>", description = "Run only this"
                + " application from the stack, at the same address it has as a stack member.")
        String appName;

        @Option(names = {"--env"}, paramLabel = "<profile>", description = "Environment "
                + "profile: merges config/env/<profile>.yml between the base config and "
                + "the Studio overlay (also TESSERAQL_ENV). Profiles are the applications'; "
                + "the stack file has none - its location is its environment.")
        String envProfile;

        @Option(names = {
                "--log-format"}, paramLabel = "<text|json>", description = "Log line format (default text; json for structured logs).")
        String logFormat;

        @Option(names = {
                "--log-level"}, paramLabel = "<level>", description = "Log threshold: trace|debug|info|warn|error (default info).")
        String logLevel;

        @Option(names = {
                "--port"}, description = "The port the gateway fronts every app on (default 8080).")
        int port = 8080;

        @Option(names = {"--watch"}, description = "Watch every application's web/, workflow/, "
                + "and shared-definition trees (decisions/, rules/, scope/, domains/) and "
                + "hot-reload on save - the editor-first alternative to Studio's Apply: a "
                + "route edit bounces that route, a workflow edit rebuilds its transition "
                + "endpoints, a shared-definition edit rebuilds every route. Jobs, "
                + "consumers, and config/ changes still need a restart.")
        boolean watch;

        @Option(names = {"--modules"}, description = "Directory of optional plugin module jars "
                + "(e.g. the pdf/excel file-format codecs) to load onto the runtime classpath, "
                + "composed onto every application in the stack.")
        File modules;

        @Option(names = {
                "--embedded-db"}, arity = "0..1", paramLabel = "<data-dir>", fallbackValue = "", description = "Run with an embedded PostgreSQL (no external "
                        + "database): one server, one database, shared by the stack - "
                        + "applications isolate with currentSchema in their own URLs, and the "
                        + "framework state rides the shared database so one sign-in carries. "
                        + "Pass a directory to persist data across restarts; omit it for "
                        + "an ephemeral run.")
        String embeddedDb;

        @Option(names = {"--embedded-db-port"}, paramLabel = "<port>", description = "Bind the "
                + "embedded PostgreSQL to a fixed TCP port (default: a random free port chosen at "
                + "startup). Use it to connect a local client (e.g. psql) at a stable address. "
                + "Listens on localhost only.")
        Integer embeddedDbPort;

        @Option(names = {
                "--embedded-db-version"}, paramLabel = "<version>", description = "Pin the "
                        + "embedded PostgreSQL binary version (e.g. 17.10.0). Default: the CLI's built-in "
                        + "version, or, for a persistent data directory, the version it was created with. A "
                        + "persistent directory records the version that ran it and re-resolves that version "
                        + "on later starts, so bumping the default never breaks an existing directory.")
        String embeddedDbVersion;

        @Override
        public Integer call() throws InterruptedException {
            // The stack: named, or discovered one level up from the working directory
            // (docs/cli-surface.md decision 9) - the development loop guesses so the developer
            // does not have to type; only the refusal is ever a surprise, and it prints the fix.
            io.tesseraql.operations.app.AppDirectory.Resolved resolved;
            try {
                if (stack != null) {
                    io.tesseraql.operations.app.AppDirectory.stack(stack);
                    resolved = io.tesseraql.operations.app.AppDirectory.resolve(stack);
                } else {
                    resolved = io.tesseraql.operations.app.AppDirectory
                            .discover(Path.of(".").toAbsolutePath().normalize());
                }
            } catch (io.tesseraql.core.error.TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }
            Path stackDir = resolved.root();

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

            // Every stack member's declared tesseraql.modules set, resolved into work/modules
            // (lock-verified) and composed with an explicit --modules directory into ONE
            // classloader for the process. Interim until decision 28 wires modules per runtime:
            // the same process-wide seam serve used for one application, widened to the stack.
            List<File> moduleDirs = new ArrayList<>();
            List<Path> homes = resolved.applications();
            for (Path home : homes) {
                AppConfig config = new ManifestLoader().load(home).config();
                new ModulesInstaller().install(home, config, false).ifPresent(result -> {
                    moduleDirs.add(result.cacheDir().toFile());
                    System.out.println("Resolved " + result.artifacts().size()
                            + " tesseraql.modules artifact(s) for " + home.getFileName() + ".");
                });
            }
            if (modules != null) {
                moduleDirs.add(modules);
            }
            Thread.currentThread().setContextClassLoader(CliModules.classLoaderOver(moduleDirs,
                    Thread.currentThread().getContextClassLoader()));
            // Custom expression functions (ExpressionFunction SPI) install from the same composed
            // classloader, so routes parse and evaluate with the full function set from boot.
            io.tesseraql.core.expr.ExpressionFunctions
                    .install(Thread.currentThread().getContextClassLoader());
            // JDBC drivers arriving as module jars register through a base-classpath shim;
            // DriverManager refuses drivers whose class the caller's classloader cannot see.
            io.tesseraql.cli.modules.ModuleDrivers
                    .register(Thread.currentThread().getContextClassLoader());

            // Optionally start an embedded PostgreSQL: one server, one database, shared by the
            // stack (docs/cli-surface.md decision 4b). It supplies the framework datasource -
            // the CLI started the server, so the coordinate is not derived from any application -
            // and each application's main pool is pointed at it carrying the application's own
            // declared query string, so currentSchema isolation stays in the application's URL.
            DataSources.MainDatasourceOverride dbOverride = null;
            EmbeddedPostgresSupport.Handle embedded = null;
            if (embeddedDb != null) {
                Path dataDir = embeddedDb.isEmpty() ? null : Path.of(embeddedDb);
                try {
                    embedded = EmbeddedPostgresSupport.start(dataDir, embeddedDbPort,
                            embeddedDbVersion, false);
                } catch (EmbeddedPostgresVersionMismatchException ex) {
                    // A recoverable operator error (incompatible data directory) - a clear
                    // message, not a stack trace.
                    System.err.println(ex.getMessage());
                    return 1;
                }
                dbOverride = embedded.override();
                // The first-login hand-off, per application: leave the (random-port) JDBC URL
                // where a second terminal's `identity-schema --app <home>` finds it.
                for (Path home : homes) {
                    EmbeddedDbMarker.write(home, embedded.jdbcUrl());
                }
                System.out.println("Embedded PostgreSQL " + embedded.version() + " started"
                        + (dataDir == null ? " (ephemeral)." : " at " + dataDir + "."));
                System.out.printf("  Connect a local client on port %d: %s "
                        + "(no password; localhost only).%n", embedded.port(), embedded.jdbcUrl());
            } else if (embeddedDbPort != null || embeddedDbVersion != null) {
                System.err.println(
                        "--embedded-db-port and --embedded-db-version are ignored without --embedded-db.");
            }

            // dev may default the external origin, because the development gateway knows its own
            // address by construction; host must not (docs/stack-architecture.md decision 22).
            io.tesseraql.runtime.DevMode dev = new io.tesseraql.runtime.DevMode(dbOverride,
                    "http://localhost:" + port);
            io.tesseraql.runtime.MultiAppGateway gateway;
            try {
                gateway = io.tesseraql.runtime.MultiAppGateway.start(stackDir, port,
                        new io.tesseraql.runtime.MultiAppGateway.Settings(), appName, dev);
            } catch (io.tesseraql.core.error.TqlException refused) {
                System.err.println(refused.getMessage());
                return 2;
            }

            EmbeddedPostgresSupport.Handle embeddedToClose = embedded;
            List<Path> markedHomes = embeddedToClose == null ? List.of() : homes;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                gateway.close();
                // Stop the embedded postgres only after the runtimes released their connections.
                if (embeddedToClose != null) {
                    embeddedToClose.close();
                    markedHomes.forEach(EmbeddedDbMarker::delete);
                }
            }));

            System.out.println("TesseraQL dev: " + gateway.appNames().size()
                    + " app(s) on port " + gateway.port() + ". Press Ctrl+C to stop.");
            for (String name : gateway.appNames()) {
                System.out.println("  http://localhost:" + gateway.port() + "/" + name + "/");
            }
            // One-time, best-effort, per application: an unseeded identity store stalls every
            // entry path at the login form, so say how to create the first administrator.
            for (Path home : homes) {
                AppConfig config = new ManifestLoader().load(home).config();
                FirstAdminHint.check(config, home, dbOverride).ifPresent(System.out::println);
            }
            // The editor-first instant loop (dev --watch): a daemon-thread file watcher per
            // runtime funnels saves under each application's web/ into the same hot reload
            // Studio's Apply triggers; the Ctrl+C shutdown hook above stops it with the gateway.
            if (watch) {
                gateway.watchRoutes(System.out::println);
                System.out.println(
                        "Watching every application's web/ for changes; save a route file to hot-reload it.");
            }
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
