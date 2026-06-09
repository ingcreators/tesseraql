package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
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

    static List<String> tenantIds(AppConfig config, DataSource mainDataSource, TenantDataSources pools) {
        Object staticList = config.navigate("tenancy.tenants");
        if (staticList instanceof List<?> list && !list.isEmpty()) {
            return list.stream().map(String::valueOf).toList();
        }
        String sql = config.getString("tenancy.registry.sql").orElse(null);
        if (sql != null && !sql.isBlank()) {
            return query(mainDataSource, sql);
        }
        if (!pools.isEmpty()) {
            return List.copyOf(pools.tenantIds());
        }
        return List.of();
    }

    private static List<String> query(DataSource dataSource, String sql) {
        List<String> ids = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
        } catch (SQLException ex) {
            throw new TqlException(REGISTRY_ERROR, "Tenant registry query failed: " + ex.getMessage());
        }
        return ids;
    }
}
