package io.tesseraql.runtime;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the framework's own schema migrations (design ch. 31): the {@code V1__*.sql} scripts
 * bundled with each framework module ({@code tesseraql/db/migration/<component>}) run through
 * Flyway with a per-component history table ({@code tql_schema_history__<component>}), giving
 * operators a versioned record of the framework tables and serializing concurrent node startups
 * through Flyway's lock. The scripts stay idempotent, so databases created by earlier framework
 * versions (where the stores created their tables directly) baseline cleanly.
 */
final class FrameworkMigrations {

    private static final Logger LOG = LoggerFactory.getLogger(FrameworkMigrations.class);
    private static final String[] COMPONENTS = {"security", "operations"};

    private FrameworkMigrations() {
    }

    /** Migrates every framework component on the main datasource. */
    static void migrate(DataSource dataSource) {
        if (!isPostgres(dataSource)) {
            // Other dialects rely on the stores' idempotent schema bootstrap; the versioned
            // history needs the matching Flyway database module first (design ch. 42).
            LOG.info("Framework migrations run on PostgreSQL only; skipping");
            return;
        }
        for (String component : COMPONENTS) {
            int applied = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:tesseraql/db/migration/" + component)
                    .table("tql_schema_history__" + component)
                    .baselineOnMigrate(true)
                    .baselineVersion("0")
                    .load()
                    .migrate()
                    .migrationsExecuted;
            if (applied > 0) {
                LOG.info("Applied {} framework migration(s) for {}", applied, component);
            }
        }
    }

    private static boolean isPostgres(DataSource dataSource) {
        try (java.sql.Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getDatabaseProductName()
                    .toLowerCase(java.util.Locale.ROOT).contains("postgres");
        } catch (java.sql.SQLException ex) {
            return false;
        }
    }
}
