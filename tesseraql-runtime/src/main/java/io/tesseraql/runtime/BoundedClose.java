package io.tesseraql.runtime;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Waits for a close that follows the drain, for a bound the platform's grace period can cover
 * (docs/deployment-maturity.md decision 4).
 *
 * <p>After the in-flight requests drain, the stop closes the host's Vert.x, the outbound client
 * and the gateway's own Vert.x — futures that complete in milliseconds when they complete at all.
 * Each used to be waited for under the gateway's <em>start</em> timeout, sixty seconds, thirty
 * for the host: a hundred and fifty seconds of permitted waiting behind a grace period derived
 * as the drain bound plus fifteen. A close that has not completed in three seconds will not
 * complete — the measured ones take milliseconds — and four sit in sequence on the gateway's
 * stop path (the front server, the host's Vert.x, the outbound client, the gateway's own
 * Vert.x), so three each leaves the JVM its exit inside the margin. Abandoning one is logged,
 * and the exit is then bounded by construction rather than by luck.
 */
final class BoundedClose {

    private static final Logger LOG = LoggerFactory.getLogger(BoundedClose.class);

    /** The bound every post-drain close waits under; four in sequence fit inside the margin. */
    static final Duration BOUND = Duration.ofSeconds(3);

    private BoundedClose() {
    }

    /**
     * Waits for {@code closing} up to {@code bound}.
     *
     * @return whether it completed inside the bound; an abandoned close is logged at WARN, a
     *         failed one at DEBUG — neither is the stop's to throw
     */
    static boolean await(Future<?> closing, Duration bound, String what) {
        try {
            closing.get(bound.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (TimeoutException late) {
            LOG.warn("{} did not close within {}; abandoning it so the stop can end", what,
                    bound);
            return false;
        } catch (ExecutionException | RuntimeException failed) {
            LOG.debug("{} did not close cleanly", what, failed);
            return false;
        }
    }
}
