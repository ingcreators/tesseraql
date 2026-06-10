package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.DatabaseVendors;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;
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
 * <p>Vendor-specific scripts in {@code db/migration-<vendor>} ({@code postgresql},
 * {@code mysql}, ...) layer over the common ones, using version numbers that do not collide.
 * Apps using several databases keep one migration set per connection:
 * {@code db/<datasource>/migration[-<vendor>]} runs against
 * {@code tesseraql.datasources.<datasource>} with its own history table.
 *
 * <p>Each app gets its own history table ({@code tql_schema_history_<app>}, named datasources
 * appending {@code __<datasource>}), so several apps can share one database without colliding. In
 * schema-per-tenant / database-per-tenant modes the main migrations also run against every
 * configured tenant pool. During a canary, migrations must stay backward compatible with the
 * previous version (expand/contract). Disable per app with
 * {@code tesseraql.migrations.enabled: false}.
 */
final class AppMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(AppMigrations.class);
    private static final TqlErrorCode UNKNOWN_DATASOURCE = new TqlErrorCode(TqlDomain.APP, 4201);

    private AppMigrations() {
    }

    /**
     * Applies {@code appHome}'s migrations: the main set to the main datasource and every tenant
     * pool, and each {@code db/<datasource>/migration} set to its named datasource.
     */
    static void migrate(String appName, Path appHome, AppConfig config,
            DataSource mainDataSource, TenantDataSources tenantDataSources,
            Function<String, DataSource> namedDataSources) {
        if (!config.getString("tesseraql.migrations.enabled")
                .map(Boolean::parseBoolean).orElse(true)) {
            LOG.info("Migrations disabled; skipping db/migration for app {}", appName);
            return;
        }
        Path main = appHome.resolve("db/migration");
        if (Files.isDirectory(main)) {
            String historyTable = historyTable(appName);
            run(main, historyTable, mainDataSource, appName, "main");
            for (String tenantId : tenantDataSources.tenantIds()) {
                run(main, historyTable,
                        tenantDataSources.dataSourceFor(tenantId, mainDataSource),
                        appName, tenantId);
            }
        }
        for (Path migrations : namedMigrationDirs(appHome)) {
            String datasource = migrations.getParent().getFileName().toString();
            DataSource dataSource = namedDataSources.apply(datasource);
            if (dataSource == null) {
                throw new TqlException(UNKNOWN_DATASOURCE, "db/" + datasource
                        + "/migration has no matching tesseraql.datasources." + datasource);
            }
            run(migrations, historyTable(appName) + "__" + sanitize(datasource),
                    dataSource, appName, datasource);
        }
    }

    private static void run(Path migrations, String historyTable, DataSource dataSource,
            String appName, String target) {
        int applied = Flyway.configure()
                .dataSource(dataSource)
                .locations(locations(migrations, dataSource))
                .failOnMissingLocations(false)
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

    /** The common directory plus the connected vendor's sibling ({@code migration-<vendor>}). */
    private static String[] locations(Path migrations, DataSource dataSource) {
        List<String> locations = new ArrayList<>();
        locations.add("filesystem:" + migrations);
        DatabaseVendors.vendor(dataSource)
                .map(vendor -> migrations.resolveSibling(migrations.getFileName() + "-" + vendor))
                .filter(Files::isDirectory)
                .ifPresent(vendorDir -> locations.add("filesystem:" + vendorDir));
        return locations.toArray(String[]::new);
    }

    /** Every {@code db/<datasource>/migration} directory, ordered by datasource name. */
    private static List<Path> namedMigrationDirs(Path appHome) {
        Path db = appHome.resolve("db");
        if (!Files.isDirectory(db)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(db)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().startsWith("migration"))
                    .map(dir -> dir.resolve("migration"))
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** A per-app history table name, so apps sharing a database do not collide. */
    static String historyTable(String appName) {
        return "tql_schema_history_" + sanitize(appName);
    }

    private static String sanitize(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }
}
