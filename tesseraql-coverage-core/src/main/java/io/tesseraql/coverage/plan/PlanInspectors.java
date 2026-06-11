package io.tesseraql.coverage.plan;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Optional;

/**
 * Selects the {@link PlanInspector} for a dialect id (design ch. 42, 46.5). New dialect inspectors are
 * registered here so the plan guard works across databases.
 */
public final class PlanInspectors {

    private static final TqlErrorCode UNSUPPORTED = new TqlErrorCode(TqlDomain.PLAN, 1502);

    private PlanInspectors() {
    }

    /** The inspector for {@code dialect}, or empty when none is registered. */
    public static Optional<PlanInspector> find(String dialect) {
        if (dialect == null) {
            return Optional.empty();
        }
        return switch (dialect.toLowerCase(java.util.Locale.ROOT)) {
            case "postgres", "postgresql" -> Optional.of(new PostgresPlanInspector());
            case "mysql", "mariadb" -> Optional.of(new MysqlPlanInspector());
            case "oracle" -> Optional.of(new OraclePlanInspector());
            case "sqlserver" -> Optional.of(new SqlServerPlanInspector());
            default -> Optional.empty();
        };
    }

    /** The inspector for {@code dialect}, or a {@link TqlException} when unsupported. */
    public static PlanInspector forDialect(String dialect) {
        return find(dialect).orElseThrow(() -> new TqlException(UNSUPPORTED,
                "No query plan inspector for dialect '" + dialect + "'"));
    }
}
