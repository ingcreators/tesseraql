package io.tesseraql.runtime;

import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * The wait before the front closes at a stop (docs/deployment-maturity.md decision 10, the M10
 * proof): the in-flight count down to zero, then quiet for a whole linger. The count covers
 * the requests the relay has accepted and not the one a keep-alive client has already put on
 * the wire — its next request, sent before the response carrying its {@code Connection: close}
 * arrived — and a close at the first zero cut exactly those: two in half a million through a
 * rolling restart under eight closed-loop workers. A front that has been quiet for a linger has
 * answered every client's last request with the header, and every client has gone.
 */
final class QuietDrain {

    /** How often the count is read while requests are in flight. */
    static final long POLL_MILLIS = 50;

    /** How long the front stays open after the count last reached zero. */
    static final long LINGER_MILLIS = 250;

    /** Sleeps for the millis; false when interrupted, which ends the wait. */
    @FunctionalInterface
    interface Sleeper {
        boolean sleep(long millis);
    }

    private QuietDrain() {
    }

    /**
     * Waits until the count has been zero for a whole linger, or the bound ran out, or the
     * sleeper was interrupted.
     *
     * @return true when the front is quiet; false when requests are still in flight
     */
    static boolean await(IntSupplier inFlight, Duration bound, Sleeper sleeper,
            LongSupplier nanoTime) {
        long deadline = nanoTime.getAsLong() + bound.toNanos();
        while (nanoTime.getAsLong() < deadline) {
            if (inFlight.getAsInt() > 0) {
                if (!sleeper.sleep(POLL_MILLIS)) {
                    break;
                }
                continue;
            }
            if (!sleeper.sleep(LINGER_MILLIS)) {
                break;
            }
            if (inFlight.getAsInt() == 0) {
                return true;
            }
        }
        return inFlight.getAsInt() == 0;
    }

    /** The sleeper a stop uses: {@link Thread#sleep}, the interrupt kept for the caller. */
    static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
