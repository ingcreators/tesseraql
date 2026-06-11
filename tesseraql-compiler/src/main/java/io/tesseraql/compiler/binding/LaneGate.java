package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.threading.ExecutionLanes;
import io.tesseraql.core.threading.Lane;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.spi.Synchronization;

/**
 * Admits a request onto a named execution lane, applying backpressure (design ch. 24).
 *
 * <p>The permit is acquired on the inbound thread before the route hands off to the lane executor;
 * requests beyond the lane's {@code maxConcurrency} are rejected with {@code TQL-LANE-5031} (503,
 * service unavailable) rather than queued. The permit is released when the exchange completes. When
 * no lanes are configured the gate is a no-op so apps without threading config behave unchanged.
 */
public final class LaneGate implements Processor {

    private static final TqlErrorCode CAPACITY = new TqlErrorCode(TqlDomain.LANE, 5031);

    private final String laneName;

    public LaneGate(String laneName) {
        this.laneName = laneName;
    }

    @Override
    public void process(Exchange exchange) {
        ExecutionLanes lanes = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.LANES_BEAN, ExecutionLanes.class);
        if (lanes == null || !lanes.has(laneName)) {
            return;
        }
        Lane lane = lanes.lane(laneName);
        if (!lane.tryAdmit()) {
            throw new TqlException(CAPACITY, "Execution lane '" + laneName + "' is at capacity");
        }
        exchange.getExchangeExtension().addOnCompletion(new Synchronization() {
            @Override
            public void onComplete(Exchange completed) {
                lane.release();
            }

            @Override
            public void onFailure(Exchange failed) {
                lane.release();
            }
        });
    }
}
