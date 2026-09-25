package io.tesseraql.runtime;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.tenant.PoolRole;
import io.tesseraql.pipeline.tenant.TenantDataSourceResolver;
import io.tesseraql.yaml.config.AppConfig;
import java.util.EnumMap;
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
public final class TenantDataSources implements TenantDataSourceResolver, AutoCloseable {

    private static final TqlErrorCode NO_TENANT_DATASOURCE = new TqlErrorCode(TqlDomain.TENANT,
            4031);
    /**
     * TQL-TENANT-4032: {@code tenancy.mode} names no isolation mode, or a per-tenant mode declares
     * no {@code tenancy.datasources} — the boot refuses rather than serving every tenant the
     * shared pool (docs/multi-tenancy.md).
     */
    private static final TqlErrorCode INVALID_TENANCY_CONFIG = new TqlErrorCode(TqlDomain.TENANT,
            4032);
    /** The isolation modes (docs/multi-tenancy.md); anything else is a refusal, not a fallback. */
    static final java.util.Set<String> MODES = java.util.Set.of("shared-schema",
            "schema-per-tenant", "database-per-tenant");

    private final Map<String, HikariDataSource> byTenant;
    /** Each tenant's role pools (docs/capacity-defaults.md decision 5a); absent roles stay on its pool. */
    private final Map<String, Map<PoolRole, HikariDataSource>> rolesByTenant;
    private final boolean perTenant;

    private TenantDataSources(Map<String, HikariDataSource> byTenant,
            Map<String, Map<PoolRole, HikariDataSource>> rolesByTenant, boolean perTenant) {
        this.byTenant = byTenant;
        this.rolesByTenant = rolesByTenant;
        this.perTenant = perTenant;
    }

    static TenantDataSources load(AppConfig config) {
        return load(config, null);
    }

    /** As {@link #load(AppConfig)}, binding each tenant pool's driver from the module loader. */
    static TenantDataSources load(AppConfig config, ClassLoader moduleLoader) {
        String mode = config.getString("tenancy.mode").orElse("");
        boolean perTenant = "database-per-tenant".equals(mode) || "schema-per-tenant".equals(mode);
        Object node = config.navigate("tenancy.datasources");
        // A misspelled mode used to read as "not per-tenant": no pools, no resolver, every
        // tenant on the shared pool, and the shared-schema lint switched off with it — the
        // isolation whose whole contract is structural, gone on a typo, silently
        // (docs/audit-low-leads.md G25). An enabled tenancy names one of the three modes or the
        // boot refuses; a per-tenant mode with no pools would refuse every tenant, so it is
        // refused once, here.
        if (config.getBoolean("tenancy.enabled", false)) {
            if (!mode.isEmpty() && !MODES.contains(mode)) {
                throw new TqlException(INVALID_TENANCY_CONFIG, "tenancy.mode '" + mode
                        + "' is not an isolation mode; use shared-schema, schema-per-tenant or"
                        + " database-per-tenant");
            }
            if (perTenant && (!(node instanceof Map<?, ?> declared) || declared.isEmpty())) {
                throw new TqlException(INVALID_TENANCY_CONFIG, "tenancy.mode '" + mode
                        + "' isolates tenants by pool but tenancy.datasources declares none;"
                        + " every tenant would be refused (TQL-TENANT-4031)");
            }
        }
        if (!perTenant || !(node instanceof Map<?, ?> datasources) || datasources.isEmpty()) {
            return new TenantDataSources(Map.of(), Map.of(), perTenant);
        }
        Map<String, HikariDataSource> built = new LinkedHashMap<>();
        Map<String, Map<PoolRole, HikariDataSource>> roles = new LinkedHashMap<>();
        try {
            for (Object key : datasources.keySet()) {
                String tenant = String.valueOf(key);
                String prefix = "tenancy.datasources." + tenant + ".";
                built.put(tenant, DataSources.create(
                        config, "tesseraql-tenant-" + tenant, prefix, moduleLoader));
                Map<PoolRole, HikariDataSource> tenantRoles = new EnumMap<>(PoolRole.class);
                roles.put(tenant, tenantRoles);
                for (PoolRole role : MainRoles.ROLES) {
                    String sizing = roleSizing(config, prefix, role);
                    if (sizing != null) {
                        tenantRoles.put(role, DataSources.createRole(config,
                                "tesseraql-tenant-" + tenant + "-" + MainRoles.suffix(role),
                                prefix, null, sizing, moduleLoader));
                    }
                }
            }
        } catch (RuntimeException failed) {
            // The pools already open would otherwise outlive a boot that refused.
            roles.values().forEach(owned -> owned.values().forEach(HikariDataSource::close));
            built.values().forEach(HikariDataSource::close);
            throw failed;
        }
        return new TenantDataSources(Map.copyOf(built), Map.copyOf(roles), perTenant);
    }

    /**
     * Where a tenant's role pool reads its sizing (docs/capacity-defaults.md decision 5a): the
     * tenant block's own role block, else main's, else {@code null} — the tenant keeps that work
     * on its own pool, exactly as {@code main} does when it declares no such role.
     */
    private static String roleSizing(AppConfig config, String tenantPrefix, PoolRole role) {
        String own = tenantPrefix + MainRoles.key(role);
        if (config.navigate(own) != null) {
            return own + ".";
        }
        String mains = "tesseraql.datasources.main." + MainRoles.key(role);
        return config.navigate(mains) != null ? mains + "." : null;
    }

    boolean isEmpty() {
        return byTenant.isEmpty();
    }

    /** The configured tenant ids, in declaration order. */
    java.util.Set<String> tenantIds() {
        return byTenant.keySet();
    }

    /**
     * The tenant's own pool. In a per-tenant isolation mode a tenant with no configured pool is
     * rejected with {@code TQL-TENANT-4031} rather than falling back — running its job against the
     * shared pool would read and write another tenant's rows, and per-tenant isolation is
     * structural (no {@code tenant.id} predicate). In shared-schema mode there are no per-tenant
     * pools by design, so every tenant resolves to {@code fallback} (scoped by the {@code tenant.id}
     * bind).
     */
    DataSource dataSourceFor(String tenantId, DataSource fallback) {
        HikariDataSource pool = byTenant.get(tenantId);
        if (pool != null) {
            return pool;
        }
        if (perTenant) {
            throw new TqlException(NO_TENANT_DATASOURCE,
                    "No datasource configured for tenant '" + tenantId + "' in a per-tenant "
                            + "isolation mode; add a tenancy.datasources." + tenantId + " block "
                            + "(a shared-pool fallback would expose another tenant's data)");
        }
        return fallback;
    }

    /**
     * As {@link #dataSourceFor(String, DataSource)}, for work of {@code role}
     * (docs/capacity-defaults.md decision 5a): the tenant's role pool where it has one, else its
     * own pool — refused, as ever, for an unknown tenant in a per-tenant mode. In shared-schema
     * mode there are no tenant pools, so {@code fallback} is main's pool for the role.
     */
    DataSource dataSourceFor(String tenantId, DataSource fallback, PoolRole role) {
        HikariDataSource rolePool = rolePool(tenantId, role);
        return rolePool != null ? rolePool : dataSourceFor(tenantId, fallback);
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
    public DataSource resolve(String tenantId, PoolRole role) {
        HikariDataSource rolePool = rolePool(tenantId, role);
        return rolePool != null ? rolePool : resolve(tenantId);
    }

    private HikariDataSource rolePool(String tenantId, PoolRole role) {
        Map<PoolRole, HikariDataSource> roles = rolesByTenant.get(tenantId);
        return roles == null || role == PoolRole.ONLINE ? null : roles.get(role);
    }

    @Override
    public void close() {
        rolesByTenant.values().forEach(roles -> roles.values().forEach(HikariDataSource::close));
        byTenant.values().forEach(HikariDataSource::close);
    }
}
