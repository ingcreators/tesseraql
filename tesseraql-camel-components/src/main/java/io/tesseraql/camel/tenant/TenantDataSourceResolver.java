package io.tesseraql.camel.tenant;

import javax.sql.DataSource;

/**
 * Resolves the {@link DataSource} to execute against for a given tenant (design ch. 30.2).
 *
 * <p>Bound into the registry by the runtime when tenancy uses a per-tenant isolation mode
 * (database-per-tenant or schema-per-tenant). The SQL component consults it when the exchange
 * carries a resolved tenant; shared-schema and untenanted routes fall back to the named datasource.
 */
@FunctionalInterface
public interface TenantDataSourceResolver {

    /** Returns the datasource for {@code tenantId}, or {@code null} to fall back to the default. */
    DataSource resolve(String tenantId);
}
