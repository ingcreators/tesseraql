package io.tesseraql.compiler.binding;

import io.tesseraql.core.files.FileTransferService;
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
 */
final class TransferScope {

    private TransferScope() {
    }

    /** The transfer, when the route that asks is the route that created it. */
    static Optional<FileTransferService.TransferStatus> own(FileTransferService transfers,
            String transferId, String appName, String routeId) {
        if (transfers == null) {
            return Optional.empty();
        }
        return transfers.status(transferId)
                .filter(status -> Objects.equals(status.appName(), appName)
                        && Objects.equals(status.routeId(), routeId));
    }
}
