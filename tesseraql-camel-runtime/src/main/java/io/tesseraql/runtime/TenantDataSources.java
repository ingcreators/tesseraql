package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.camel.tenant.TenantDataSourceResolver;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Per-tenant connection pools for the {@code database-per-tenant} and {@code schema-per-tenant}
 * isolation modes (design ch. 30.2). Built from the {@code tenancy.datasources} block, one pool per
 * tenant:
 * <pre>
 * tenancy:
 *   mode: database-per-tenant
 *   datasources:
 *     acme:   { jdbcUrl: ..., username: ..., password: ... }
 *     globex: { jdbcUrl: ..., username: ..., password: ... }
 * </pre>
 *
 * <p>A resolved tenant with no configured pool is rejected with {@code TQL-TENANT-4031} (403) rather
 * than silently falling back to a shared pool, preventing cross-tenant data exposure.
 */
final class TenantDataSources implements TenantDataSourceResolver, AutoCloseable {

    private static final TqlErrorCode NO_TENANT_DATASOURCE = new TqlErrorCode(TqlDomain.TENANT, 4031);

    private final Map<String, HikariDataSource> byTenant;

    private TenantDataSources(Map<String, HikariDataSource> byTenant) {
        this.byTenant = byTenant;
    }

    static TenantDataSources load(AppConfig config) {
        String mode = config.getString("tenancy.mode").orElse("");
        boolean perTenant = "database-per-tenant".equals(mode) || "schema-per-tenant".equals(mode);
        Object node = config.navigate("tenancy.datasources");
        if (!perTenant || !(node instanceof Map<?, ?> datasources) || datasources.isEmpty()) {
            return new TenantDataSources(Map.of());
        }
        Map<String, HikariDataSource> built = new LinkedHashMap<>();
        for (Object key : datasources.keySet()) {
            String tenant = String.valueOf(key);
            built.put(tenant, DataSources.create(
                    config, "tesseraql-tenant-" + tenant, "tenancy.datasources." + tenant + "."));
        }
        return new TenantDataSources(Map.copyOf(built));
    }

    boolean isEmpty() {
        return byTenant.isEmpty();
    }

    /** The configured tenant ids, in declaration order. */
    java.util.Set<String> tenantIds() {
        return byTenant.keySet();
    }

    /** The tenant's own pool, or {@code fallback} (the shared pool) when none is configured. */
    DataSource dataSourceFor(String tenantId, DataSource fallback) {
        HikariDataSource pool = byTenant.get(tenantId);
        return pool != null ? pool : fallback;
    }

    @Override
    public DataSource resolve(String tenantId) {
        DataSource dataSource = byTenant.get(tenantId);
        if (dataSource == null) {
            throw new TqlException(NO_TENANT_DATASOURCE,
                    "No datasource configured for tenant '" + tenantId + "'");
        }
        return dataSource;
    }

    @Override
    public void close() {
        byTenant.values().forEach(HikariDataSource::close);
    }
}
