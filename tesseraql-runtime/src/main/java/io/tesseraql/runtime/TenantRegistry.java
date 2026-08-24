package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.sql.SqlStatement;
import io.tesseraql.core.sql.SqlStatementException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.SqlDefaults;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Resolves the directory of tenant ids for per-tenant batch fan-out (design ch. 30.3).
 *
 * <p>In shared-schema mode there is no per-tenant datasource to enumerate, so the tenant list comes
 * from, in precedence order: a static {@code tenancy.tenants} list, a {@code tenancy.registry.sql}
 * query against the main datasource (first column), or the configured per-tenant datasource keys.
 */
final class TenantRegistry {

    private static final TqlErrorCode REGISTRY_ERROR = new TqlErrorCode(TqlDomain.TENANT, 5005);

    private TenantRegistry() {
    }

    static List<String> tenantIds(AppConfig config, DataSource mainDataSource,
            TenantDataSources pools) {
        Object staticList = config.navigate("tenancy.tenants");
        if (staticList instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        String sql = config.getString("tenancy.registry.sql").orElse(null);
        if (sql != null && !sql.isBlank()) {
            return query(config, mainDataSource, sql);
        }
        if (!pools.isEmpty()) {
            return List.copyOf(pools.tenantIds());
        }
        return List.of();
    }

    /**
     * User-declared SQL, so it runs through the statement primitive like every other declared
     * statement: bounded by {@code tesseraql.sql.timeoutSeconds}, classified, spanned. It ran
     * on a raw {@code createStatement} — unbounded and invisible — which is exactly the defect
     * class docs/contract-sql-execution.md closed. The refusal keeps its own code.
     */
    private static List<String> query(AppConfig config, DataSource dataSource, String sql) {
        try {
            return SqlStatement.on(dataSource)
                    .timeoutSeconds(SqlDefaults.timeoutSeconds(config))
                    .surface("job")
                    .read("tenancy.registry.sql", SqlRenderer.render(sql, Map.of()),
                            (rs, span) -> {
                                List<String> ids = new ArrayList<>();
                                while (rs.next()) {
                                    ids.add(rs.getString(1));
                                }
                                return ids;
                            });
        } catch (SqlStatementException ex) {
            throw new TqlException(REGISTRY_ERROR,
                    "Tenant registry query failed: " + ex.getMessage());
        }
    }
}
