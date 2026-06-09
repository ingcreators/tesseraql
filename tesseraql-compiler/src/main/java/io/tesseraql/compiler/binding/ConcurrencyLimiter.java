package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.concurrent.Semaphore;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.Synchronization;

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
    public Processor acquire() {
        return exchange -> {
            if (!semaphore.tryAcquire()) {
                throw new TqlException(RATE_LIMIT, "Too many concurrent requests");
            }
            exchange.getExchangeExtension().addOnCompletion(new Synchronization() {
                @Override
                public void onComplete(Exchange completed) {
                    semaphore.release();
                }

                @Override
                public void onFailure(Exchange failed) {
                    semaphore.release();
                }
            });
        };
    }
}
