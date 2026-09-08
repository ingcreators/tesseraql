package io.tesseraql.core.diag;

import java.time.Duration;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Feeds a {@link PinningMonitor} from the JDK Flight Recorder {@code jdk.VirtualThreadPinned} event
 * (design ch. 24). Runs an async JFR stream that records each pinning over the configured threshold;
 * closing the source stops the stream.
 */
public final class JfrPinningSource implements AutoCloseable {

    private static final String EVENT = "jdk.VirtualThreadPinned";

    /** The event's own carrier. {@code getThread()} is the PINNED virtual thread, not this. */
    private static final String CARRIER = "carrierThread";

    /** What the VM says held the carrier, e.g. "VM call to Foo.&lt;clinit&gt; on stack". */
    private static final String REASON = "pinnedReason";

    private final RecordingStream stream;

    public JfrPinningSource(PinningMonitor monitor, Duration threshold) {
        this.stream = new RecordingStream();
        stream.enable(EVENT).withThreshold(threshold);
        stream.onEvent(EVENT, event -> monitor.record(toEvent(event)));
        stream.startAsync();
    }

    private static PinningEvent toEvent(RecordedEvent event) {
        long durationMs = event.getDuration() != null ? event.getDuration().toMillis() : 0L;
        // The event's own start time, not the clock: JFR delivers in flushed batches, so reading
        // the wall clock here stamps dispatch rather than pinning — and collapses a whole batch
        // onto one instant, reporting pins hundreds of milliseconds apart as simultaneous.
        return new PinningEvent(carrier(event), durationMs, site(event),
                event.getStartTime().toEpochMilli());
    }

    /**
     * The carrier the virtual thread was pinned to.
     *
     * <p>{@code event.getThread()} is the pinned <em>virtual</em> thread — the empty string for an
     * unnamed one — so it never answered the question this field asks. The carrier is a field on
     * the event, read behind {@code hasField} so an older or newer JDK degrades rather than throws.
     */
    private static String carrier(RecordedEvent event) {
        if (event.hasField(CARRIER)
                && event.getValue(CARRIER) instanceof RecordedThread thread
                && thread.getJavaName() != null && !thread.getJavaName().isBlank()) {
            return thread.getJavaName();
        }
        return "unknown";
    }

    /**
     * Where the pinning came from: the innermost frame that is not platform code.
     *
     * <p>Frame 0 is {@code java.lang.VirtualThread.postPinnedEvent} for every event this stream
     * will ever see — the VM's own reporting frame — and the frames under it are the park
     * machinery, whose depth varies by pinning shape. Walking down to the first frame outside
     * {@code java.*}, {@code javax.*}, {@code jdk.*} and {@code sun.*} finds the application
     * instead, which is the only frame an operator can act on.
     */
    private static String site(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            // Not hasField: the field is declared even on an event carrying no stack at all.
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            String type = frame.getMethod().getType().getName();
            if (!platform(type)) {
                return type + "." + frame.getMethod().getName();
            }
        }
        // A stack that is platform code all the way down. The VM's own words beat a frame that
        // names only its reporting machinery.
        return event.hasField(REASON) && event.getValue(REASON) instanceof String reason
                ? reason
                : null;
    }

    private static boolean platform(String type) {
        return type.startsWith("java.") || type.startsWith("javax.")
                || type.startsWith("jdk.") || type.startsWith("sun.");
    }

    @Override
    public void close() {
        stream.close();
    }
}
