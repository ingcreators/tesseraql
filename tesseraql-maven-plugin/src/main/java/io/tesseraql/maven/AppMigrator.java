package io.tesseraql.maven;

import io.tesseraql.core.util.DatabaseVendors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Applies an app's Flyway scripts from the build (design ch. 18, 31), using the same conventions
 * as the runtime's mount-time migrations ({@code AppMigrations}): the {@code main} datasource
 * migrates {@code db/migration}, a named datasource migrates {@code db/<datasource>/migration}
 * into its own history table, and {@code migration-<vendor>} siblings layer vendor-specific
 * scripts over the common ones. Running the goal before a deploy and mounting the app afterwards
 * converge on the same history.
 */
final class AppMigrator {

    private AppMigrator() {
    }

    /** The outcome: how many migrations applied, and into which history table. */
    record Result(int applied, String historyTable) {
    }

    /**
     * Applies the migration set of {@code datasource} ({@code main} = {@code db/migration},
     * otherwise {@code db/<datasource>/migration}); empty when the directory does not exist.
     */
    static Optional<Result> migrate(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        boolean main = "main".equals(datasource);
        Path migrations = main
                ? appHome.resolve("db/migration")
                : appHome.resolve("db").resolve(datasource).resolve("migration");
        if (!Files.isDirectory(migrations)) {
            return Optional.empty();
        }
        String historyTable = main
                ? historyTable(appName)
                : historyTable(appName) + "__" + sanitize(datasource);
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + migrations);
        DatabaseVendors.vendor(dataSource)
                .map(vendor -> migrations.resolveSibling(migrations.getFileName() + "-" + vendor))
                .filter(Files::isDirectory)
                .ifPresent(vendorDir -> locations.add("filesystem:" + vendorDir));
        int applied = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .failOnMissingLocations(false)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
                .migrationsExecuted;
        return Optional.of(new Result(applied, historyTable));
    }

    /** Must stay aligned with the runtime's {@code AppMigrations.historyTable}. */
    static String historyTable(String appName) {
        return "tql_schema_history_" + sanitize(appName);
    }

    private static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
