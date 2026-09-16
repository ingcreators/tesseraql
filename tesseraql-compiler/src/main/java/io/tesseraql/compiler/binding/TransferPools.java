package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.pipeline.tenant.TenantRouting;

/**
 * The pool a route-triggered file transfer runs its own SQL on (docs/multi-tenancy.md): the
 * request's tenant pool in a per-tenant isolation mode, {@code main} otherwise — resolved through
 * the same {@link TenantRouting} every other executor uses, so a tenant whose reads are refused
 * ({@code TQL-TENANT-4031}) has its export and import refused here, before a transfer row exists,
 * rather than answered from the shared pool.
 *
 * <p>Both file recipes ran on the main pool in every mode until 0.18.0. The parity record had
 * deferred them as "a file transfer runs with no caller"; a route-triggered transfer has one, and
 * its tenant was resolved on the exchange all along (docs/audit-low-leads.md G24).
 */
final class TransferPools {

    private TransferPools() {
    }

    /** The exchange's pool and the tenant it was resolved for. */
    static FileTransferService.TransferPool of(Exchange exchange) {
        String tenantId = exchange
                .getProperty(TesseraqlProperties.TENANT) instanceof TenantContext tenant
                        ? tenant.id()
                        : null;
        return new FileTransferService.TransferPool(TenantRouting.dataSource(exchange, "main"),
                tenantId);
    }
}
