package io.tesseraql.maven;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Applies an app's {@code db/migration} Flyway scripts from the build (design ch. 18, 31), using
 * the same conventions as the runtime's mount-time migrations ({@code AppMigrations}): a per-app
 * history table {@code tql_schema_history_<app>} and baseline 0 so existing databases (e.g. with
 * the framework's {@code tql_*} tables) still take the app's V1+ scripts. Running the goal before
 * a deploy and mounting the app afterwards converge on the same history.
 */
final class AppMigrator {

    private AppMigrator() {
    }

    /** The outcome: how many migrations applied, and into which history table. */
    record Result(int applied, String historyTable) {
    }

    /** Applies {@code appHome/db/migration}; returns empty when the app has no migrations. */
    static java.util.Optional<Result> migrate(Path appHome, String appName, DataSource dataSource) {
        Path migrations = appHome.resolve("db/migration");
        if (!Files.isDirectory(migrations)) {
            return java.util.Optional.empty();
        }
        String historyTable = historyTable(appName);
        int applied = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .table(historyTable)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
                .migrationsExecuted;
        return java.util.Optional.of(new Result(applied, historyTable));
    }

    /** Must stay aligned with the runtime's {@code AppMigrations.historyTable}. */
    static String historyTable(String appName) {
        return "tql_schema_history_"
                + appName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
