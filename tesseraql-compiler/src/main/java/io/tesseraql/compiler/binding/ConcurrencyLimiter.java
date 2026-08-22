package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import java.util.concurrent.Semaphore;

/**
 * Limits the number of in-flight requests for a route (design ch. 36.1). Each route has its own
 * limiter; requests beyond {@code maxInFlight} are rejected with {@code TQL-RATE-4291} (429) rather
 * than queued, protecting downstream resources from overload.
 */
public final class ConcurrencyLimiter {

    private static final TqlErrorCode RATE_LIMIT = new TqlErrorCode(TqlDomain.RATE, 4291);

    private final Semaphore semaphore;

    public ConcurrencyLimiter(int maxInFlight) {
        this.semaphore = new Semaphore(maxInFlight);
    }

    /** Returns a processor that acquires a permit and releases it when the exchange completes. */
    public Step acquire() {
        return new Gate();
    }

    /** Named so the recipe-governance matrix test can read it back off the compiled route. */
    final class Gate implements Step {
        @Override
        public void process(Exchange exchange) {
            if (!semaphore.tryAcquire()) {
                throw new TqlException(RATE_LIMIT, "Too many concurrent requests");
            }
            exchange.addOnCompletion(done -> semaphore.release());
        }
    }
}
