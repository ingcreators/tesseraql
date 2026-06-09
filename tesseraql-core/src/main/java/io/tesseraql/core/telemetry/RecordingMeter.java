package io.tesseraql.core.telemetry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** An in-memory {@link Meter} that totals counter values, for diagnostics and tests (design ch. 25). */
public final class RecordingMeter implements Meter {

    private final ConcurrentHashMap<String, AtomicLong> totals = new ConcurrentHashMap<>();

    @Override
    public Counter counter(String name) {
        AtomicLong total = totals.computeIfAbsent(name, key -> new AtomicLong());
        return (delta, attributes) -> total.addAndGet(delta);
    }

    /** Returns the accumulated value of a counter, or 0 if it was never used. */
    public long total(String name) {
        AtomicLong total = totals.get(name);
        return total == null ? 0 : total.get();
    }

    public Map<String, Long> totals() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        totals.forEach((name, value) -> snapshot.put(name, value.get()));
        return snapshot;
    }
}
