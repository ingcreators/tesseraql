package io.tesseraql.cli;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.ResponseHeaderDefaults;
import io.tesseraql.yaml.config.SecurityDefaults;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql scaffold}: code generators over an existing app home (roadmap Phase 23).
 * Regeneration is idempotent; files the user edited (or owns outright) are skipped and reported
 * unless {@code --force} is given — the checksum contract of design ch. 22.20.
 */
@Command(name = "scaffold", description = "Generate code into an existing app.", subcommands = {
        ScaffoldCommand.CrudCommand.class,
        ScaffoldCommand.DecisionCommand.class,
        ScaffoldCommand.EjectViewCommand.class
})
final class ScaffoldCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    /** {@code tesseraql scaffold crud --app <dir> --table <t>}: a table's CRUD slice. */
    @Command(name = "crud", description = "Scaffold list/detail/edit routes, 2-way SQL, pages,"
            + " and tests for a table.")
    static final class CrudCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the app home.")
        Path app;

        @Option(names = {"--table"}, required = true, description = "The table to scaffold.")
        String table;

        @Mixin
        CliDatasource datasource;

        @Option(names = {"--force"}, description = "Overwrite edited and user-owned files.")
        boolean force;

        /** The tables the app's code catalogs read, so a maintenance screen invalidates them. */
        private static java.util.Set<String> catalogTables(java.nio.file.Path appHome) {
            return io.tesseraql.yaml.catalog.Catalogs.load(appHome).all().values().stream()
                    .map(io.tesseraql.yaml.model.CatalogSpec::table)
                    .filter(java.util.Objects::nonNull)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public Integer call() throws Exception {
            Path home = SingleApplication.resolve(app, "tesseraql scaffold crud");
            if (home == null) {
                return 2;
            }
            app = home;
            AppConfig config = new ManifestLoader().load(app).config();
            TableSchema schema = introspect(config);
            List<ScaffoldedFile> files = new CrudScaffolder(SecurityDefaults.from(config),
                    ResponseHeaderDefaults.from(config), catalogTables(app))
                    .scaffold(schema);
            ScaffoldWriter.Report report = new ScaffoldWriter().apply(app, files, force);

            report.written().forEach(path -> System.out.println("  wrote     " + path));
            report.unchanged().forEach(path -> System.out.println("  unchanged " + path));
            report.skipped().forEach(path -> System.out.println("  skipped   " + path));
            printHints(config);
            if (report.blocked()) {
                System.out.println("Some files were skipped: they were edited by hand or carry"
                        + " no scaffold checksum. Rerun with --force to overwrite them.");
                return 1;
            }
            return 0;
        }

        /**
         * Connects with the shared {@link CliDatasource} resolution: an explicit
         * {@code --jdbc-url}, then the app's main datasource, then a running
         * {@code serve --embedded-db} (its {@code work/embedded-db.jdbc} marker) when the
         * config does not resolve or answer — so scaffolding works against the embedded
         * database another terminal is serving, like {@code identity-schema} does.
         */
        private TableSchema introspect(AppConfig config) throws Exception {
            try (Connection connection = datasource.resolve(config, app).getConnection()) {
                return new TableIntrospector().introspect(connection, table);
            }
        }

        /** Setup the generated files assume; missing pieces get a hint, not a failure. */
        private void printHints(AppConfig config) {
            Object policies = config.navigate("tesseraql.security.policies");
            boolean hasPolicies = policies instanceof Map<?, ?> map
                    && map.containsKey("app.read") && map.containsKey("app.write");
            if (!hasPolicies) {
                System.out.println("Hint: the generated routes reference the app.read /"
                        + " app.write policies; define them under tesseraql.security.policies"
                        + " or edit the generated security blocks.");
            }
            if (!Files.isRegularFile(app.resolve("templates/nav.html"))) {
                System.out.println("Hint: the generated pages reference"
                        + " ~{templates/nav.html :: app-nav}; create templates/nav.html"
                        + " (tesseraql new generates one).");
            }
        }
    }

    /**
     * {@code tesseraql scaffold decision --app <dir> --name shippingFee --inputs
     * weight:between,region:eq --outputs fee,carrier}: a table-backed decision's declaration
     * plus its typed backing-table migration (docs/decision-tables.md). The maintenance
     * surface is one {@code scaffold crud} run over the generated table after migrating.
     */
    @Command(name = "decision", description = "Scaffold a table-backed decision: the"
            + " decisions/ declaration and the typed backing-table migration.")
    static final class DecisionCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the app home.")
        Path app;

        @Option(names = {
                "--name"}, required = true, description = "The decision name (lowerCamel), e.g. shippingFee.")
        String name;

        @Option(names = {
                "--inputs"}, required = true, split = ",", description = "Inputs as name:kind (eq, between, in, bool, orgSubtree).")
        java.util.List<String> inputs;

        @Option(names = {"--outputs"}, required = true, split = ",", description = "Output names.")
        java.util.List<String> outputs;

        @Option(names = {
                "--unique"}, description = "hitPolicy: unique (default: first, with a priority column).")
        boolean unique;

        @Option(names = {
                "--effective"}, description = "Dated rows: valid_from/valid_to matched against effectiveAt:.")
        boolean effective;

        @Option(names = {"--force"}, description = "Overwrite edited and user-owned files.")
        boolean force;

        @Override
        public Integer call() throws Exception {
            Path home = SingleApplication.resolve(app, "tesseraql scaffold decision");
            if (home == null) {
                return 2;
            }
            app = home;
            java.util.Map<String, String> parsed = new java.util.LinkedHashMap<>();
            for (String input : inputs) {
                String[] parts = input.split(":", 2);
                parsed.put(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "eq");
            }
            List<ScaffoldedFile> files = new io.tesseraql.yaml.scaffold.DecisionScaffolder()
                    .scaffold(name, parsed, outputs, unique, effective, nextMigrationVersion());
            ScaffoldWriter.Report report = new ScaffoldWriter().apply(app, files, force);

            report.written().forEach(path -> System.out.println("  wrote     " + path));
            report.unchanged().forEach(path -> System.out.println("  unchanged " + path));
            report.skipped().forEach(path -> System.out.println("  skipped   " + path));
            System.out.println("Next: migrate, then `tesseraql scaffold crud --app " + app
                    + " --table " + files.get(1).path()
                            .replaceAll(".*__decision_(.+)\\.sql", "$1")
                    + "_rules` for the"
                    + " maintenance routes, and reference the decision from a route's or"
                    + " transition's decide: block.");
            if (report.blocked()) {
                System.out.println("Some files were skipped: they were edited by hand or carry"
                        + " no scaffold checksum. Rerun with --force to overwrite them.");
                return 1;
            }
            return 0;
        }

        /** The next free {@code V<n>} under {@code db/migration}, the Studio numbering rule. */
        private int nextMigrationVersion() throws Exception {
            Path dir = app.resolve("db/migration");
            int highest = 0;
            if (Files.isDirectory(dir)) {
                try (var entries = Files.list(dir)) {
                    highest = entries
                            .map(file -> file.getFileName().toString())
                            .filter(file -> file.matches("V\\d+__.*\\.sql"))
                            .mapToInt(file -> Integer.parseInt(
                                    file.substring(1, file.indexOf("__"))))
                            .max()
                            .orElse(0);
                }
            }
            return highest + 1;
        }
    }

    /**
     * {@code tesseraql scaffold eject-view --app <dir> --route web/…/get.yml}: the customization
     * ladder's L3 (docs/declarative-views.md) — render the route's declarative view into a real,
     * hand-owned template (checksum-stamped like every scaffold artifact) and flip the route from
     * {@code view:} to {@code template:}. The view document stays on disk for reference; delete
     * it when done.
     */
    @Command(name = "eject-view", description = "Eject a route's declarative view into a"
            + " hand-owned template and flip the route to template:.")
    static final class EjectViewCommand implements Callable<Integer> {

        @Option(names = {"--app"}, required = true, description = "Path to the app home.")
        Path app;

        @Option(names = {
                "--route"}, required = true, description = "App-relative route file (e.g. web/items/get.yml).")
        String route;

        @Option(names = {"--force"}, description = "Overwrite an edited or user-owned template.")
        boolean force;

        @Override
        public Integer call() throws Exception {
            Path home = SingleApplication.resolve(app, "tesseraql scaffold eject-view");
            if (home == null) {
                return 2;
            }
            app = home;
            // The orchestration is shared with Studio's eject ramp (docs/page-builder.md
            // D2): ViewEjects locates the route, renders the pattern, writes the
            // checksum-stamped template and flips view: to template:.
            var manifest = new ManifestLoader().load(app);
            io.tesseraql.yaml.view.ViewEjects.Result result;
            try {
                result = io.tesseraql.yaml.view.ViewEjects.eject(app, manifest, route, force);
            } catch (io.tesseraql.core.error.TqlException ex) {
                // Failure diagnostics go to stderr so `tesseraql scaffold eject-view … > out`
                // does not swallow the reason behind a non-zero exit.
                System.err.println(ex.getMessage());
                return 1;
            }
            if (result.blocked()) {
                System.err.println("  skipped   " + result.templatePath());
                System.err.println("The target template exists with hand edits."
                        + " Rerun with --force to overwrite it.");
                return 1;
            }
            System.out.println("  wrote     " + result.templatePath());
            System.out.println("  flipped   " + result.routePath()
                    + " (view: -> template:)");
            System.out.println("The view document no longer drives rendering; delete it"
                    + " when you are done.");
            return 0;
        }
    }
}
