package io.tesseraql.cli.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.Correlation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The property that makes a trace id worth having: a step handed to another thread still logs
 * with the ids of the request that started it. Asserted here rather than in the runtime module
 * because the CLI's structured log provider supplies the real MDC adapter — the runtime's tests
 * run on {@code slf4j-simple}, whose adapter is a no-op, so a test like this would pass there
 * without proving anything.
 *
 * <p>The mechanism is {@link Correlation}, which is what an execution lane's handoff goes through
 * (docs/camel-removal.md decision 5). It used to be Camel's {@code MDCService}, which wrapped
 * every processor a route reified — and a pipeline reifies nothing, so the wrapping quietly
 * stopped happening when the edge started running pipelines.
 */
class MdcAcrossThreadsTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theCorrelationIdsFollowAStepAcrossAThreadHandoff() throws Exception {
        ExecutorService lane = Executors.newSingleThreadExecutor();
        try {
            MDC.put("traceId", "trace-1");
            MDC.put("spanId", "span-1");
            AtomicReference<String> traceOnFarSide = new AtomicReference<>();
            AtomicReference<String> spanOnFarSide = new AtomicReference<>();
            AtomicReference<String> farThread = new AtomicReference<>();

            lane.submit(Correlation.carry(() -> {
                traceOnFarSide.set(MDC.get("traceId"));
                spanOnFarSide.set(MDC.get("spanId"));
                farThread.set(Thread.currentThread().getName());
                return null;
            })).get();

            assertThat(farThread.get()).isNotEqualTo(Thread.currentThread().getName());
            assertThat(traceOnFarSide.get()).isEqualTo("trace-1");
            assertThat(spanOnFarSide.get()).isEqualTo("span-1");
        } finally {
            lane.shutdownNow();
        }
    }

    /**
     * A lane is a pool, so the thread has to be left clean.
     *
     * <p>Without this the next request's steps log under the previous request's trace id, which
     * is worse than logging under none: an operator following a trace finds lines that were never
     * part of it.
     */
    @Test
    void theHandoffLeavesNoIdsBehindOnThePoolThread() throws Exception {
        ExecutorService lane = Executors.newSingleThreadExecutor();
        try {
            MDC.put("traceId", "trace-1");
            lane.submit(Correlation.carry(() -> null)).get();

            AtomicReference<String> leftBehind = new AtomicReference<>("not run");
            lane.submit(() -> leftBehind.set(MDC.get("traceId"))).get();

            assertThat(leftBehind.get()).isNull();
        } finally {
            lane.shutdownNow();
        }
    }
}
