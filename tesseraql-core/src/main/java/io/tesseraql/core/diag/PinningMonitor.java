package io.tesseraql.core.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates virtual-thread pinning samples in-process for diagnostics (design ch. 24): a total
 * count plus a bounded ring of the most recent events. Safe for concurrent use; fed by a JFR source
 * such as {@link JfrPinningSource}.
 */
public final class PinningMonitor {

    private final int capacity;
    private final AtomicLong count = new AtomicLong();
    private final ArrayDeque<PinningEvent> ring;

    public PinningMonitor(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.ring = new ArrayDeque<>(capacity);
    }

    /** Records a pinning event, incrementing the count and adding it to the bounded ring. */
    public synchronized void record(PinningEvent event) {
        count.incrementAndGet();
        if (ring.size() == capacity) {
            ring.removeFirst();
        }
        ring.addLast(event);
    }

    /** Total pinning events observed over the monitor's lifetime. */
    public long count() {
        return count.get();
    }

    /** The retained pinning samples, most recent first. */
    public synchronized List<PinningEvent> recent() {
        List<PinningEvent> snapshot = new ArrayList<>(ring);
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }
}
