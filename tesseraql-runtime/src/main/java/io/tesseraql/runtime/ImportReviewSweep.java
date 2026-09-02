package io.tesseraql.runtime;

import io.tesseraql.core.files.FileTransferService;
import java.time.Clock;

/**
 * The timer that reclaims parked import batches past their review window
 * (docs/csv-import.md decision 2).
 *
 * <p><strong>Always on</strong>, unlike the transfer retention sweep beside it. That sweep is
 * opt-in because a produced export file is something the app chose to make and may want to keep;
 * a parked batch is the user's own uploaded bytes, held only so a confirm can spend them, and
 * nobody chose to store those. The bytes go and the row stays — which is what lets a late confirm
 * be told the batch expired rather than that the token never existed.
 *
 * <p>The period is derived from the window rather than inherited from the hourly transfer sweep:
 * a thirty-minute window swept hourly would leave bytes on disk for up to ninety minutes, three
 * times the advertised life. A quarter of the window keeps the overshoot under a quarter.
 */
final class ImportReviewSweep {

    private static final System.Logger LOG = System
            .getLogger(ImportReviewSweep.class.getName());

    /** Not more often than this, however short the window: the sweep costs a query. */
    private static final long MINIMUM_PERIOD_MILLIS = 60_000L;

    private final FileTransferService transfers;
    private final long reviewTtlMillis;
    private final Clock clock;

    ImportReviewSweep(FileTransferService transfers, long reviewTtlMillis, Clock clock) {
        this.transfers = transfers;
        this.reviewTtlMillis = reviewTtlMillis;
        this.clock = clock;
    }

    /** A quarter of the review window, never under a minute. */
    long periodMillis() {
        return Math.max(MINIMUM_PERIOD_MILLIS, reviewTtlMillis / 4);
    }

    void schedule(Schedules schedules) {
        schedules.every("tql.transfers.review", periodMillis(), () -> {
            // The cutoff is now: a batch carries its own expiry, set from the window that was
            // configured when it was parked, so a reconfigured window never strands old rows.
            int expired = transfers.expireReviewBatches(clock.instant());
            if (expired > 0) {
                LOG.log(System.Logger.Level.INFO,
                        "Reclaimed {0} unconfirmed import batch(es)", expired);
            }
        });
    }
}
