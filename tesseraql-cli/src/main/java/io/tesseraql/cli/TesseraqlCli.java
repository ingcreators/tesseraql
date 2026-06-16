package io.tesseraql.cli;

import io.tesseraql.cli.mcp.McpCommand;
import io.tesseraql.runtime.TesseraqlRuntime;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * TesseraQL command-line interface (design ch. 17). The short command alias is {@code tql}.
 */
@Command(name = "tesseraql", mixinStandardHelpOptions = true, version = "TesseraQL 0.2.0-SNAPSHOT", description = "SQL-first hypermedia and integration framework.", subcommands = {
        TesseraqlCli.ServeCommand.class,
        TesseraqlCli.RoutesCommand.class,
        NewCommand.class,
        ScaffoldCommand.class,
        LintCommand.class,
        GenerateCommand.class,
        GovernanceCommand.class,
        PackageCommand.class,
        VerifyCommand.class,
        McpCommand.class
})
public final class TesseraqlCli implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
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

        @Override
        public Integer call() throws InterruptedException {
            // Load any opt-in plugin modules (file-format codecs, ...) so route compilation and the
            // runtime discover them via the FileCodec ServiceLoader (which uses this classloader).
            Thread.currentThread().setContextClassLoader(CliModules.classLoader(modules,
                    Thread.currentThread().getContextClassLoader()));
            TesseraqlRuntime runtime = port != null
                    ? TesseraqlRuntime.start(app, port)
                    : TesseraqlRuntime.start(app);
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close));
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
