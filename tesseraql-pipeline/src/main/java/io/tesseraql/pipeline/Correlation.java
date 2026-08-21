package io.tesseraql.pipeline;

import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.MDC;

/**
 * Carries a request's log correlation across a thread handoff (docs/camel-removal.md decision 5).
 *
 * <p>The trace and span ids reach the log through SLF4J's MDC, which is thread-local — so a step
 * handed to an execution lane logs without them unless somebody carries them over. Camel's
 * {@code MDCService} used to, by wrapping every processor a route reified; a pipeline reifies
 * nothing, so the wrapping stopped happening when the edge started running pipelines and nothing
 * said so. This is the replacement, and it is deliberately one method: the framework has exactly
 * one thread handoff inside a request.
 *
 * <p>It also clears the MDC afterwards, which the service did too and which matters more here: a
 * lane is a <em>pool</em>, so a thread that kept the last request's ids would attribute the next
 * request's log lines to it.
 */
public final class Correlation {

    private Correlation() {
    }

    /** Wraps {@code task} so it runs with the calling thread's MDC and leaves the pool clean. */
    public static <T> Callable<T> carry(Callable<T> task) {
        Map<String, String> correlation = MDC.getCopyOfContextMap();
        return () -> {
            if (correlation != null) {
                MDC.setContextMap(correlation);
            }
            try {
                return task.call();
            } finally {
                MDC.clear();
            }
        };
    }
}
