package io.tesseraql.cli;

import io.tesseraql.cli.modules.ModuleCoordinate;
import io.tesseraql.cli.modules.ModulesInstaller;
import io.tesseraql.cli.modules.ModulesYaml;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql modules}: manage the opt-in {@code tesseraql.modules} set (drivers and the
 * pdf/excel/s3 codecs) — declarative and reproducible via {@code modules.lock} (design:
 * app-developer-distribution work item 4). {@code add} edits {@code config/tesseraql.yml} and
 * refreshes the lock (like {@code cargo add}); {@code resolve} (re)writes the lock; {@code list}
 * prints the declared set. {@code serve} resolves the same set on start, verifying the lock.
 */
@Command(name = "modules", description = "Manage the opt-in tesseraql.modules set.", subcommands = {
        ModulesCommand.AddCommand.class,
        ModulesCommand.ResolveCommand.class,
        ModulesCommand.ListCommand.class
})
final class ModulesCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** {@code tesseraql modules add <coord> --app <dir>}. */
    @Command(name = "add", description = "Add a coordinate to tesseraql.modules and refresh modules.lock.")
    static final class AddCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Module coordinate: group:artifact[:version].")
        String coordinate;

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Option(names = {"--offline"}, description = "Resolve only from the local repository.")
        boolean offline;

        @Override
        public Integer call() throws Exception {
            ModuleCoordinate.parse(coordinate);
            Path tesseraqlYml = app.resolve("config/tesseraql.yml");
            String updated = ModulesYaml.addModule(Files.readString(tesseraqlYml), coordinate);
            Files.writeString(tesseraqlYml, updated);
            System.out.println("Added " + coordinate + " to " + tesseraqlYml);

            AppConfig config = new ManifestLoader().load(app).config();
            new ModulesInstaller(offline).install(app, config, true)
                    .ifPresent(result -> System.out.println("Resolved " + result.artifacts().size()
                            + " artifact(s) into " + result.cacheDir() + "; wrote modules.lock"));
            return 0;
        }
    }

    /** {@code tesseraql modules resolve --app <dir>}. */
    @Command(name = "resolve", description = "Resolve tesseraql.modules and (re)write modules.lock.")
    static final class ResolveCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Option(names = {"--offline"}, description = "Resolve only from the local repository.")
        boolean offline;

        @Override
        public Integer call() {
            AppConfig config = new ManifestLoader().load(app).config();
            return new ModulesInstaller(offline).install(app, config, true).map(result -> {
                System.out.println("Resolved " + result.artifacts().size() + " artifact(s) into "
                        + result.cacheDir() + "; wrote modules.lock");
                return 0;
            }).orElseGet(() -> {
                System.out.println("No tesseraql.modules declared.");
                return 0;
            });
        }
    }

    /** {@code tesseraql modules list --app <dir>}. */
    @Command(name = "list", description = "List the declared tesseraql.modules.")
    static final class ListCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Override
        public Integer call() {
            AppConfig config = new ManifestLoader().load(app).config();
            List<ModuleCoordinate> declared = ModulesYaml.declared(config);
            if (declared.isEmpty()) {
                System.out.println("No tesseraql.modules declared.");
                return 0;
            }
            declared.forEach(coordinate -> System.out.println("  " + coordinate.canonical()));
            return 0;
        }
    }
}
