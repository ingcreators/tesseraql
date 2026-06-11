package io.tesseraql.runtime;

import io.tesseraql.core.util.DatabaseVendors;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the framework's own schema migrations (design ch. 31, 42): the {@code V1__*.sql}
 * scripts bundled with each framework module run through Flyway with a per-component history
 * table ({@code tql_schema_history__<component>}), giving operators a versioned record of the
 * framework tables and serializing concurrent node startups through Flyway's lock.
 *
 * <p>Vendors whose DDL diverges from the portable PostgreSQL/MySQL scripts keep complete scripts
 * of their own in a {@code <component>-<vendor>} location (Oracle, SQL Server); when one exists
 * it replaces the common location entirely. The scripts stay idempotent, so databases created by
 * the stores' direct bootstrap baseline cleanly.
 */
final class FrameworkMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(FrameworkMigrations.class);
    /** Each component's V1 file, used to probe whether a vendor-specific location exists. */
    private static final Map<String, String> COMPONENTS = Map.of(
            "security", "V1__framework_sessions.sql",
            "operations", "V1__framework_operations.sql");
    /** Vendors whose Flyway database module ships with the runtime. */
    private static final Set<String> FLYWAY_BUNDLED =
            Set.of("postgresql", "mysql", "oracle", "sqlserver");

    private FrameworkMigrations() {
    }

    /** Migrates every framework component on the main datasource. */
    static void migrate(DataSource dataSource) {
        String vendor = DatabaseVendors.vendor(dataSource).orElse(null);
        if (vendor == null || !FLYWAY_BUNDLED.contains(vendor)) {
            // Other dialects rely on the stores' idempotent schema bootstrap; the versioned
            // history needs the matching Flyway database module first (design ch. 42).
            LOG.info("No bundled Flyway support for '{}'; skipping framework migrations", vendor);
            return;
        }
        for (Map.Entry<String, String> component : COMPONENTS.entrySet()) {
            int applied = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:" + location(component.getKey(),
                            component.getValue(), vendor))
                    .table("tql_schema_history__" + component.getKey())
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            if (applied > 0) {
                LOG.info("Applied {} framework migration(s) for {} ({})",
                        applied, component.getKey(), vendor);
            }
        }
    }

    /** The vendor-specific location when it exists, the common one otherwise. */
    private static String location(String component, String v1File, String vendor) {
        String vendorDir = "tesseraql/db/migration/" + component + "-" + vendor;
        if (FrameworkMigrations.class.getClassLoader()
                .getResource(vendorDir + "/" + v1File) != null) {
            return vendorDir;
        }
        return "tesseraql/db/migration/" + component;
    }
}
