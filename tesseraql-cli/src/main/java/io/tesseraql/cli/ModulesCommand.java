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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * {@code tesseraql modules}: manage the opt-in {@code tesseraql.modules} set (drivers and the
 * pdf/excel/s3 codecs) — declarative and reproducible via {@code modules.lock} (design:
 * app-developer-distribution work item 4). {@code add} edits {@code config/tesseraql.yml} and
 * refreshes the lock (like {@code cargo add}); {@code resolve} (re)writes the lock; {@code fetch} fills a
 * portable bag for a disconnected machine; {@code list} prints the declared set. {@code serve} resolves the same set on start, verifying the lock.
 */
@Command(name = "modules", description = "Manage the opt-in tesseraql.modules set.", subcommands = {
        ModulesCommand.AddCommand.class,
        ModulesCommand.ResolveCommand.class,
        ModulesCommand.FetchCommand.class,
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

        @Mixin
        ConfigOptions configOptions;

        @Option(names = {"--offline"}, description = "Resolve only from the local repository.")
        boolean offline;

        @Override
        public Integer call() throws Exception {
            configOptions.apply();
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

    /** {@code tesseraql modules resolve --app <dir>}, or {@code --stack} for every member. */
    @Command(name = "resolve", description = "Resolve tesseraql.modules and (re)write modules.lock.")
    static final class ResolveCommand implements Callable<Integer> {

        @Option(names = {"--app"}, description = "Path to the external app home.")
        Path app;

        @Option(names = {"--stack"}, paramLabel = "<dir>", description = "Resolve every member"
                + " of the stack: an install root (catalog.json) or a folder of application"
                + " homes — the operator step a host's declared-but-unresolved refusal names.")
        Path stack;

        @Mixin
        ConfigOptions configOptions;

        @Option(names = {"--offline"}, description = "Resolve only from the local repository.")
        boolean offline;

        @Override
        public Integer call() {
            configOptions.apply();
            if ((app == null) == (stack == null)) {
                System.err.println("Pass exactly one of --app <dir> or --stack <dir>.");
                return 2;
            }
            List<Path> homes;
            if (stack != null) {
                try {
                    homes = io.tesseraql.operations.app.AppDirectory.stack(stack);
                } catch (io.tesseraql.core.error.TqlException refused) {
                    System.err.println(refused.getMessage());
                    return 2;
                }
            } else {
                homes = List.of(app);
            }
            for (Path home : homes) {
                AppConfig config = new ManifestLoader().load(home).config();
                new ModulesInstaller(offline).install(home, config, true).ifPresentOrElse(
                        result -> System.out.println(home.getFileName() + ": resolved "
                                + result.artifacts().size() + " artifact(s) into "
                                + result.cacheDir() + "; wrote modules.lock"),
                        () -> System.out.println(
                                home.getFileName() + ": no tesseraql.modules declared."));
            }
            return 0;
        }
    }

    /**
     * {@code tesseraql modules fetch --into <bag>}: collects, on a connected machine, everything a
     * disconnected one will need (docs/module-channel.md decision 5).
     *
     * <p>The bag is a partial local Maven repository, built by <em>resolving into</em> it rather
     * than by copying jars into a directory: an offline resolve checks poms and repository
     * metadata that a hand-assembled tree does not have, and the BOM must be present for a later
     * resolve whose declaration omits a version. What lands in it is decided by declarations —
     * each member's {@code tesseraql.modules} and the closure its {@code modules.lock} pins — with
     * one exception, {@code --platform}, because only the target machine can say which embedded
     * PostgreSQL binary it will run.
     */
    @Command(name = "fetch", description = "Fetch every module a stack needs into a portable bag.")
    static final class FetchCommand implements Callable<Integer> {

        @Option(names = {"--app"}, description = "Path to a single external app home.")
        Path app;

        @Option(names = {"--stack"}, paramLabel = "<dir>", description = "Collect for every member"
                + " of the stack: an install root (catalog.json) or a folder of application homes.")
        Path stack;

        @Option(names = {"--into"}, required = true, paramLabel = "<dir>", description = "The bag"
                + " to fill: a local Maven repository the disconnected side reads with --repo.")
        Path into;

        @Option(names = {
                "--platform"}, paramLabel = "<classifier>", split = ",", description = "Embedded PostgreSQL binaries to include, by zonky classifier"
                        + " (e.g. linux-amd64,windows-amd64). Omit for none.")
        List<String> platforms;

        @Option(names = {"--embedded-db-version"}, paramLabel = "<version>", description = "Binary"
                + " version for --platform (default: the CLI's built-in default). Pass the version"
                + " a persistent data directory is pinned to when it differs.")
        String embeddedDbVersion;

        @Mixin
        ConfigOptions configOptions;

        @Override
        public Integer call() {
            configOptions.apply();
            if ((app == null) == (stack == null)) {
                System.err.println("Pass exactly one of --app <dir> or --stack <dir>.");
                return 2;
            }
            // Every resolution below writes into the bag, which is the whole mechanism: the
            // resolver reads maven.repo.local, so pointing it at the bag makes Maven itself
            // produce the repository layout the offline side will read.
            Path bag = into.toAbsolutePath().normalize();
            System.setProperty("maven.repo.local", bag.toString());

            List<Path> homes;
            if (stack != null) {
                try {
                    homes = io.tesseraql.operations.app.AppDirectory.stack(stack);
                } catch (io.tesseraql.core.error.TqlException refused) {
                    System.err.println(refused.getMessage());
                    return 2;
                }
            } else {
                homes = List.of(app);
            }

            io.tesseraql.cli.modules.ModuleBag manifest = new io.tesseraql.cli.modules.ModuleBag();
            for (Path home : homes) {
                AppConfig config = new ManifestLoader().load(home).config();
                List<ModuleCoordinate> declared = ModulesYaml.declared(config);
                String name = String.valueOf(home.getFileName());
                if (declared.isEmpty()) {
                    System.out.println(name + ": no tesseraql.modules declared.");
                    continue;
                }
                // The lock is what says which closure was reviewed; fetching anything else would
                // fill the bag with artifacts the disconnected side then refuses.
                io.tesseraql.apptasks.PackagedModules.requireLock(home, config);
                List<io.tesseraql.cli.modules.ResolvedModule> resolved = new io.tesseraql.cli.modules.ModuleResolver(
                        ModulesInstaller.BOM_COORDINATE, false, true).resolve(declared);
                io.tesseraql.cli.modules.ModulesLock.read(home.resolve("modules.lock"))
                        .map(lock -> lock.verify(resolved))
                        .filter(problems -> !problems.isEmpty())
                        .ifPresent(problems -> {
                            throw new IllegalStateException(name
                                    + ": modules.lock verification failed:\n  "
                                    + String.join("\n  ", problems));
                        });
                manifest.add(name, resolved);
                System.out.println(name + ": fetched " + resolved.size() + " artifact(s).");
            }

            if (platforms != null) {
                String version = embeddedDbVersion != null
                        ? embeddedDbVersion
                        : EmbeddedPostgresSupport.defaultVersion();
                for (String classifier : platforms) {
                    Path jar = EmbeddedPostgresSupport.fetchBinary(classifier.trim(), version);
                    manifest.add("embedded-db", "io.zonky.test.postgres:"
                            + "embedded-postgres-binaries-" + classifier.trim() + ":" + version,
                            io.tesseraql.core.util.Hashing.sha256(jar));
                    System.out.println("embedded-db: fetched PostgreSQL " + version + " for "
                            + classifier.trim() + ".");
                }
            }

            Path written = manifest.write(bag);
            System.out.println("Bag ready at " + bag + " (" + manifest.entries().size()
                    + " artifact(s); manifest " + written.getFileName() + ").");
            System.out.println("On the disconnected machine: --repo " + bag + " --offline");
            return 0;
        }
    }

    /** {@code tesseraql modules list --app <dir>}. */
    @Command(name = "list", description = "List the declared tesseraql.modules.")
    static final class ListCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the external app home.")
        Path app;

        @Mixin
        ConfigOptions configOptions;

        @Override
        public Integer call() {
            configOptions.apply();
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
