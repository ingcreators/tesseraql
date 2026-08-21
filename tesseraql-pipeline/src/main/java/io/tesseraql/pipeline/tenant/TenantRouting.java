package io.tesseraql.pipeline.tenant;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import javax.sql.DataSource;

/**
 * Resolves the {@link DataSource} an exchange executes against (design ch. 30.2), shared by every
 * executor so reads and writes cannot disagree about which database a tenant's request belongs to.
 *
 * <p>When the request carries a resolved tenant and a {@link TenantDataSourceResolver} is bound,
 * the per-tenant datasource is used (database/schema-per-tenant); otherwise the named datasource
 * is used. Tenant routing replaces only the {@code main} connector: an explicit non-main
 * {@code datasource:} (roadmap Phase 53) is authoritative — named connectors are deployment-shared
 * infrastructure, not tenant homes.
 *
 * <p>A resolved tenant with no configured pool is rejected by the resolver
 * ({@code TQL-TENANT-4031}, 403) rather than falling back to the shared pool. That rejection has
 * to reach the write path too: a tenant whose reads are refused must not have its writes land in
 * {@code main}.
 */
public final class TenantRouting {

    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.SQL, 2502);

    private TenantRouting() {
    }

    /** The datasource for this exchange, honoring tenant routing over the {@code main} connector. */
    public static DataSource dataSource(Exchange exchange, String datasourceName) {
        Object tenant = "main".equals(datasourceName)
                ? exchange.getProperty(TesseraqlProperties.TENANT)
                : null;
        if (tenant instanceof TenantContext tenantContext) {
            TenantDataSourceResolver resolver = exchange.beans().lookup(
                    TesseraqlProperties.TENANT_DATASOURCE_RESOLVER_BEAN,
                    TenantDataSourceResolver.class);
            if (resolver != null) {
                DataSource resolved = resolver.resolve(tenantContext.id());
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        DataSource dataSource = exchange.beans().lookup(datasourceName, DataSource.class);
        if (dataSource == null) {
            throw new TqlException(NO_DATASOURCE,
                    "No DataSource named '" + datasourceName + "' in the registry");
        }
        return dataSource;
    }
}
