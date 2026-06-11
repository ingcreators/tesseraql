package io.tesseraql.core.diag;

import java.time.Duration;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;

/**
 * Feeds a {@link PinningMonitor} from the JDK Flight Recorder {@code jdk.VirtualThreadPinned} event
 * (design ch. 24). Runs an async JFR stream that records each pinning over the configured threshold;
 * closing the source stops the stream.
 */
public final class JfrPinningSource implements AutoCloseable {

    private static final String EVENT = "jdk.VirtualThreadPinned";

    private final RecordingStream stream;

    public JfrPinningSource(PinningMonitor monitor, Duration threshold) {
        this.stream = new RecordingStream();
        stream.enable(EVENT).withThreshold(threshold);
        stream.onEvent(EVENT, event -> monitor.record(toEvent(event)));
        stream.startAsync();
    }

    private static PinningEvent toEvent(RecordedEvent event) {
        String carrier = event.getThread() != null ? event.getThread().getJavaName() : "unknown";
        long durationMs = event.getDuration() != null ? event.getDuration().toMillis() : 0L;
        String topFrame = null;
        if (event.getStackTrace() != null && !event.getStackTrace().getFrames().isEmpty()) {
            var top = event.getStackTrace().getFrames().get(0);
            topFrame = top.getMethod().getType().getName() + "." + top.getMethod().getName();
        }
        return new PinningEvent(carrier, durationMs, topFrame, System.currentTimeMillis());
    }

    @Override
    public void close() {
        stream.close();
    }
}
