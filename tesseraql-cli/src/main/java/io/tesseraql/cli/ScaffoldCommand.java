package io.tesseraql.cli;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code tesseraql scaffold}: code generators over an existing app home (roadmap Phase 23).
 * Regeneration is idempotent; files the user edited (or owns outright) are skipped and reported
 * unless {@code --force} is given — the checksum contract of design ch. 22.20.
 */
@Command(name = "scaffold", description = "Generate code into an existing app.", subcommands = {
        ScaffoldCommand.CrudCommand.class
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

        @Option(names = {"--jdbc-url"}, description = "JDBC URL to introspect"
                + " (default: the app's main datasource).")
        String jdbcUrl;

        @Option(names = {"--username"}, description = "Database user for --jdbc-url.")
        String username;

        @Option(names = {"--password"}, description = "Database password for --jdbc-url.")
        String password;

        @Option(names = {"--force"}, description = "Overwrite edited and user-owned files.")
        boolean force;

        @Override
        public Integer call() throws Exception {
            AppConfig config = new ManifestLoader().load(app).config();
            TableSchema schema = introspect(config);
            List<ScaffoldedFile> files = new CrudScaffolder().scaffold(schema);
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
         * Connects to {@code --jdbc-url} with the {@code --username}/{@code --password} options,
         * or falls back to the app's main datasource (URL and credentials) when no URL is given.
         */
        private TableSchema introspect(AppConfig config) throws Exception {
            String url = jdbcUrl;
            String user = username;
            String pass = password;
            if (url == null) {
                url = config.getString("tesseraql.datasources.main.jdbcUrl").orElseThrow(
                        () -> new IllegalArgumentException("No --jdbc-url given and the app"
                                + " config declares no tesseraql.datasources.main.jdbcUrl"));
                if (user == null) {
                    user = config.getString("tesseraql.datasources.main.username").orElse(null);
                }
                if (pass == null) {
                    pass = config.getString("tesseraql.datasources.main.password").orElse(null);
                }
            }
            try (Connection connection = DriverManager.getConnection(url, user, pass)) {
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
}
