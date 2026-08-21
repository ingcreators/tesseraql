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
    private static final Set<String> FLYWAY_BUNDLED = Set.of("postgresql", "mysql", "oracle",
            "sqlserver");

    private FrameworkMigrations() {
    }

    /**
     * TQL-APP-4214: the {@code security} schema is not at the version this runtime expects.
     *
     * <p>Raised by a hosted runtime that validates instead of migrating. Either the host has not
     * migrated the schema, or this runtime resolved a different framework datasource than the
     * host migrated — which is exactly the misconfiguration the validate-don't-migrate split
     * exists to catch loudly, instead of producing a stack where sign-in silently does not carry
     * (docs/stack-architecture.md decision 16).
     */
    static final io.tesseraql.core.error.TqlErrorCode SECURITY_SCHEMA_MISMATCH = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.APP, 4214);

    /**
     * Migrates the framework components — the standalone runtime's path: {@code security}
     * (sessions — ambient framework state) follows the framework datasource
     * (docs/framework-datasource.md); {@code operations} stays on the business datasource — its
     * file set mixes buckets and its Flyway checksums pin existing deployments, so movable
     * operations-module stores bootstrap their tables on the framework datasource through their
     * own idempotent {@code ensureSchema} instead.
     */
    static void migrate(DataSource dataSource, DataSource frameworkDataSource) {
        migrateComponent("operations", dataSource);
        migrateComponent("security", frameworkDataSource);
    }

    /**
     * Migrates the stack-wide {@code security} component — the host's call, made once before any
     * runtime starts (docs/stack-architecture.md decision 16). {@code security} is stack-wide,
     * so N runtimes taking Flyway's lock on one history in turn was safe rather than correct:
     * the lock's documented purpose anticipated replicas of one application, not several
     * applications of one stack.
     */
    static void migrateSecurity(DataSource frameworkDataSource) {
        migrateComponent("security", frameworkDataSource);
    }

    /** Migrates the per-application {@code operations} component — the hosted runtime keeps this. */
    static void migrateOperations(DataSource dataSource) {
        migrateComponent("operations", dataSource);
    }

    /**
     * Validates that the {@code security} schema is at the version this runtime expects, never
     * migrating — the hosted runtime's path. The host migrated once before any runtime started,
     * so a runtime that finds anything else is misconfigured, and this refusal is the
     * wrong-framework-datasource guard: pointed at the wrong database, a runtime finds no
     * migrated schema and fails loudly at boot instead of producing a stack where sign-in
     * silently does not carry. It is also what refuses a canary whose framework-schema
     * expectation is newer than what the host migrated.
     */
    static void validateSecurity(DataSource frameworkDataSource) {
        String vendor = DatabaseVendors.vendor(frameworkDataSource).orElse(null);
        if (vendor == null || !FLYWAY_BUNDLED.contains(vendor)) {
            // Symmetric with the migrate skip: without a bundled Flyway module there is no
            // versioned history to validate against; the stores' idempotent bootstrap holds.
            LOG.info("No bundled Flyway support for '{}'; skipping framework schema validation",
                    vendor);
            return;
        }
        try {
            Flyway.configure()
                    .dataSource(frameworkDataSource)
                    .locations("classpath:" + location("security",
                            COMPONENTS.get("security"), vendor))
                    .table("tql_schema_history__security")
                    .load()
                    .validate();
        } catch (org.flywaydb.core.api.FlywayException notAtTheExpectedVersion) {
            throw new io.tesseraql.core.error.TqlException(SECURITY_SCHEMA_MISMATCH,
                    "The security schema is not at the version this runtime expects: "
                            + notAtTheExpectedVersion.getMessage()
                            + " A hosted runtime validates instead of migrating — either the"
                            + " host has not migrated the framework schema, or this runtime"
                            + " resolved a different framework datasource than the host"
                            + " migrated.",
                    notAtTheExpectedVersion);
        }
    }

    private static void migrateComponent(String component, DataSource target) {
        String v1File = COMPONENTS.get(component);
        String vendor = DatabaseVendors.vendor(target).orElse(null);
        if (vendor == null || !FLYWAY_BUNDLED.contains(vendor)) {
            // Other dialects rely on the stores' idempotent schema bootstrap; the
            // versioned history needs the matching Flyway database module first
            // (design ch. 42).
            LOG.info("No bundled Flyway support for '{}'; skipping framework"
                    + " migrations for {}", vendor, component);
            return;
        }
        int applied = Flyway.configure()
                .dataSource(target)
                .locations("classpath:" + location(component, v1File, vendor))
                .table("tql_schema_history__" + component)
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate().migrationsExecuted;
        if (applied > 0) {
            LOG.info("Applied {} framework migration(s) for {} ({})",
                    applied, component, vendor);
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
