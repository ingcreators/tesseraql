package io.tesseraql.cli;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql routes --app <dir>}: lists the routes discovered in the app. A top-level class
 * (not nested in {@link TesseraqlCli}) because the deployment distribution's root command lists it
 * too (docs/runtime-footprint.md decision 1) and must not reach through the developer CLI's class.
 */
@Command(name = "routes", description = "List the routes discovered in the app.")
final class RoutesCommand implements Callable<Integer> {

    @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
    Path app;

    @Mixin
    ConfigOptions configOptions;

    @Mixin
    CompileOptions compile;

    @Override
    public Integer call() {
        configOptions.apply();
        // Route documents parse expressions, so module-provided functions install first.
        CliModules.installAppExtensions(app, compile.modules);
        AppManifest manifest = new ManifestLoader().load(app);
        for (RouteFile route : manifest.routes()) {
            System.out.printf("%-6s %-30s %s%n",
                    route.httpMethod(), route.urlPath(), route.definition().id());
        }
        return 0;
    }
}
