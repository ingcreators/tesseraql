package io.tesseraql.runtime;

import io.tesseraql.core.util.DatabaseVendors;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the framework's own schema migrations (design ch. 31): the {@code V1__*.sql} scripts
 * bundled with each framework module ({@code tesseraql/db/migration/<component>}, plus
 * vendor-specific scripts in {@code <component>-<vendor>}) run through Flyway with a per-component
 * history table ({@code tql_schema_history__<component>}), giving operators a versioned record of
 * the framework tables and serializing concurrent node startups through Flyway's lock. The common
 * scripts stay idempotent, so databases created by earlier framework versions (where the stores
 * created their tables directly) baseline cleanly.
 */
final class FrameworkMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(FrameworkMigrations.class);
    private static final String[] COMPONENTS = {"security", "operations"};
    /** Vendors whose Flyway database module ships with the runtime. */
    private static final Set<String> FLYWAY_BUNDLED = Set.of("postgresql", "mysql");

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
        for (String component : COMPONENTS) {
            int applied = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:tesseraql/db/migration/" + component,
                            "classpath:tesseraql/db/migration/" + component + "-" + vendor)
                    .failOnMissingLocations(false)
                    .table("tql_schema_history__" + component)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            if (applied > 0) {
                LOG.info("Applied {} framework migration(s) for {} ({})",
                        applied, component, vendor);
            }
        }
    }
}
