package io.tesseraql.apptasks;

import io.tesseraql.core.util.DatabaseVendors;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Applies and inspects an app's Flyway scripts from the build (design ch. 18, 31), using the same
 * conventions as the runtime's mount-time migrations ({@code AppMigrations}): the {@code main}
 * datasource migrates {@code db/migration}, a named datasource migrates
 * {@code db/<datasource>/migration} into its own history table, and {@code migration-<vendor>}
 * siblings layer vendor-specific scripts over the common ones. Running {@link #migrate} before a
 * deploy and mounting the app afterwards converge on the same history; {@link #info},
 * {@link #validate} and {@link #repair} cover the read-only and recovery operations CI and
 * operators need without serving the app.
 */
public final class AppMigrator {

    private AppMigrator() {
    }

    /** The outcome of {@link #migrate}: how many migrations applied, and into which history table. */
    public record Result(int applied, String historyTable) {
    }

    /** The outcome of {@link #info}: the migration timeline keyed by the per-app history table. */
    public record InfoResult(String historyTable, List<Migration> migrations) {

        /** One migration row: {@code version} is blank for repeatable migrations. */
        public record Migration(String version, String description, String state,
                String installedOn) {
        }
    }

    /** The outcome of {@link #validate}: whether the history is valid and any problems found. */
    public record ValidateResult(boolean valid, String historyTable, List<String> problems) {
    }

    /** The outcome of {@link #repair}: how many history rows were removed, deleted or aligned. */
    public record RepairSummary(String historyTable, int removed, int deleted, int aligned) {
    }

    /**
     * Applies the migration set of {@code datasource} ({@code main} = {@code db/migration},
     * otherwise {@code db/<datasource>/migration}); empty when the directory does not exist.
     */
    public static Optional<Result> migrate(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        return configure(appHome, appName, datasource, dataSource)
                .map(flyway -> new Result(flyway.migrate().migrationsExecuted,
                        historyTable(appName, datasource)));
    }

    /** Reports the migration timeline without applying anything; empty when no directory exists. */
    public static Optional<InfoResult> info(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        return configure(appHome, appName, datasource, dataSource).map(flyway -> {
            List<InfoResult.Migration> migrations = Arrays.stream(flyway.info().all())
                    .map(info -> new InfoResult.Migration(
                            info.getVersion() == null ? "" : info.getVersion().toString(),
                            info.getDescription(),
                            info.getState().getDisplayName(),
                            info.getInstalledOn() == null
                                    ? null
                                    : info.getInstalledOn().toInstant().toString()))
                    .toList();
            return new InfoResult(historyTable(appName, datasource), migrations);
        });
    }

    /** Validates applied migrations against the scripts; empty when no directory exists. */
    public static Optional<ValidateResult> validate(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        return configure(appHome, appName, datasource, dataSource).map(flyway -> {
            var result = flyway.validateWithResult();
            List<String> problems = new ArrayList<>();
            result.invalidMigrations.forEach(invalid -> problems.add(
                    (invalid.version == null || invalid.version.isBlank()
                            ? ""
                            : invalid.version + " ") + invalid.description));
            if (problems.isEmpty() && !result.validationSuccessful) {
                problems.add(result.getAllErrorMessages());
            }
            return new ValidateResult(result.validationSuccessful,
                    historyTable(appName, datasource), problems);
        });
    }

    /** Repairs the history table (removes failed rows, realigns checksums); empty when no dir. */
    public static Optional<RepairSummary> repair(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        return configure(appHome, appName, datasource, dataSource).map(flyway -> {
            var result = flyway.repair();
            return new RepairSummary(historyTable(appName, datasource),
                    result.migrationsRemoved.size(), result.migrationsDeleted.size(),
                    result.migrationsAligned.size());
        });
    }

    /**
     * Builds the Flyway instance for {@code datasource}'s migration set, or empty when its
     * directory does not exist. Shared by every operation so locations, the per-app history table
     * and vendor layering stay identical across apply/info/validate/repair.
     */
    private static Optional<Flyway> configure(Path appHome, String appName, String datasource,
            DataSource dataSource) {
        boolean main = "main".equals(datasource);
        Path migrations = main
                ? appHome.resolve("db/migration")
                : appHome.resolve("db").resolve(datasource).resolve("migration");
        if (!Files.isDirectory(migrations)) {
            return Optional.empty();
        }
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + migrations);
        DatabaseVendors.vendor(dataSource)
                .map(vendor -> migrations.resolveSibling(migrations.getFileName() + "-" + vendor))
                .filter(Files::isDirectory)
                .ifPresent(vendorDir -> locations.add("filesystem:" + vendorDir));
        return Optional.of(Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .failOnMissingLocations(false)
                .table(historyTable(appName, datasource))
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load());
    }

    private static String historyTable(String appName, String datasource) {
        return "main".equals(datasource)
                ? historyTable(appName)
                : historyTable(appName) + "__" + sanitize(datasource);
    }

    /** Must stay aligned with the runtime's {@code AppMigrations.historyTable}. */
    public static String historyTable(String appName) {
        return "tql_schema_history_" + sanitize(appName);
    }

    private static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
