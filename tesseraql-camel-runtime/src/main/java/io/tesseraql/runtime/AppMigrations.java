package io.tesseraql.runtime;

import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs an app's Flyway-managed schema migrations when the app is mounted (design ch. 31, 32):
 * SQL files under {@code db/migration} (the standard {@code V1__name.sql} convention) are applied
 * before routes start, so fresh installs, upgrades and canary activations all converge through the
 * same idempotent mechanism.
 *
 * <p>Each app gets its own history table ({@code tql_schema_history_<app>}), so several apps can
 * share one database without colliding. In schema-per-tenant / database-per-tenant modes the
 * migrations also run against every configured tenant pool. During a canary, migrations must stay
 * backward compatible with the previous version (expand/contract). Disable per app with
 * {@code tesseraql.migrations.enabled: false}.
 */
final class AppMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(AppMigrations.class);

    private AppMigrations() {
    }

    /** Applies {@code appHome}'s migrations to the main datasource and every tenant pool. */
    static void migrate(String appName, Path appHome, AppConfig config,
            DataSource mainDataSource, TenantDataSources tenantDataSources) {
        Path migrations = appHome.resolve("db/migration");
        if (!Files.isDirectory(migrations)) {
            return;
        }
        if (!config.getString("tesseraql.migrations.enabled")
                .map(Boolean::parseBoolean).orElse(true)) {
            LOG.info("Migrations disabled; skipping {} for app {}", migrations, appName);
            return;
        }
        String historyTable = historyTable(appName);
        run(migrations, historyTable, mainDataSource, appName, "main");
        for (String tenantId : tenantDataSources.tenantIds()) {
            run(migrations, historyTable,
                    tenantDataSources.dataSourceFor(tenantId, mainDataSource), appName, tenantId);
        }
    }

    private static void run(Path migrations, String historyTable, DataSource dataSource,
            String appName, String target) {
        int applied = Flyway.configure()
                .dataSource(dataSource)
                .locations("filesystem:" + migrations)
                .table(historyTable)
                // Existing databases (e.g. with the framework's tql_* tables) baseline at 0 so
                // the app's V1+ migrations still apply.
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate()
                .migrationsExecuted;
        if (applied > 0) {
            LOG.info("Applied {} migration(s) for app {} on {}", applied, appName, target);
        }
    }

    /** A per-app history table name, so apps sharing a database do not collide. */
    static String historyTable(String appName) {
        return "tql_schema_history_"
                + appName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
