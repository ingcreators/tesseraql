package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
import io.tesseraql.core.tenant.TenantContext;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.Objects;
import java.util.Optional;

/**
 * A transfer as one route sees it: its own, or nothing (docs/edge-hygiene.md E0).
 *
 * <p>The {@code {transferId}} subtree — status, file, cancel — is secured like the route that
 * mounts it, so the route's policy is what stands between a caller and the bytes. A transfer
 * resolved by its id alone made every such subtree in the application a door to every transfer
 * in the database: a caller holding the id of an export started under a policy-gated route read
 * its status, its file and its card through any public file-export or file-import route's
 * subtree, and could ask it to stop. The import commit already held its batch to the app, the
 * route and the subject ({@code JdbcFileTransferService.commitImport}); this is the same rule
 * for the transfer, minus the subject — a transfer's readers are whoever the route admits, so
 * a colleague under the same policy can fetch a link the exporter shared.
 *
 * <p>A foreign transfer is indistinguishable from an unknown one: whose it is, is not for a
 * caller the route admits to learn.
 *
 * <p>And the tenant is part of "own" (docs/multi-tenancy.md): a transfer is recorded under the
 * tenant it was resolved for, and a request resolved to another tenant — or to none, where the
 * transfer had one — reads it as unknown. The subtree was scoped to app and route only, so a
 * tenant of the same deployment holding a link read another tenant's export and could cancel its
 * run (docs/audit-low-leads.md G31). A transfer recorded before 0.18.0 carries no tenant and,
 * under tenancy, is therefore reachable by no tenant — recorded, not shimmed.
 */
final class TransferScope {

    private TransferScope() {
    }

    /** The transfer, when the route that asks is the route that created it, for its tenant. */
    static Optional<FileTransferService.TransferStatus> own(FileTransferService transfers,
            String transferId, String appName, String routeId, Exchange exchange) {
        if (transfers == null) {
            return Optional.empty();
        }
        String tenantId = exchange
                .getProperty(TesseraqlProperties.TENANT) instanceof TenantContext tenant
                        ? tenant.id()
                        : null;
        return transfers.status(transferId)
                .filter(status -> Objects.equals(status.appName(), appName)
                        && Objects.equals(status.routeId(), routeId)
                        && Objects.equals(status.tenantId(), tenantId));
    }
}
