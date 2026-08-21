package io.tesseraql.runtime;

import io.tesseraql.core.files.FileTransferService;
import java.time.Clock;
import java.time.Duration;

/**
 * The timer that reclaims expired transfer files (docs/file-transfers.md, retention):
 * with {@code tesseraql.transfers.retentionDays} set, produced files older than that many
 * days are deleted from the spool and their transfers answer "no downloadable file" from
 * then on — the rows stay as history. Nothing expires by default (the DuckLake retention
 * stance: the policy belongs to the app). Every node may sweep — reclaiming is idempotent,
 * and a node-local file spool is each node's own to free.
 */
final class TransferRetentionSweep {

    private static final System.Logger LOG = System
            .getLogger(TransferRetentionSweep.class.getName());

    private final FileTransferService transfers;
    private final int retentionDays;
    private final long periodMillis;
    private final Clock clock;

    TransferRetentionSweep(FileTransferService transfers, int retentionDays, long periodMillis,
            Clock clock) {
        this.transfers = transfers;
        this.retentionDays = retentionDays;
        this.periodMillis = periodMillis;
        this.clock = clock;
    }

    void schedule(Schedules schedules) {
        schedules.every("tql.transfers.retention", periodMillis, () -> {
            int expired = transfers.expireTransfersOlderThan(
                    clock.instant().minus(Duration.ofDays(retentionDays)));
            if (expired > 0) {
                LOG.log(System.Logger.Level.INFO,
                        "Reclaimed {0} transfer file(s) older than {1} day(s)",
                        expired, retentionDays);
            }
        });
    }
}
