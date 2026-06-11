package io.tesseraql.core.diag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A bounded, in-memory slow-SQL ring buffer (design ch. 26.11): keeps the most recent executions
 * whose duration meets a threshold, discarding the oldest when full. Safe for concurrent use.
 */
public final class RingSqlExecutionLog implements SqlExecutionLog {

    private final int capacity;
    private final long thresholdMs;
    private final ArrayDeque<SqlExecution> ring;

    public RingSqlExecutionLog(int capacity, long thresholdMs) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
        this.thresholdMs = thresholdMs;
        this.ring = new ArrayDeque<>(capacity);
    }

    @Override
    public synchronized void record(SqlExecution execution) {
        if (execution.durationMs() < thresholdMs) {
            return;
        }
        if (ring.size() == capacity) {
            ring.removeFirst();
        }
        ring.addLast(execution);
    }

    @Override
    public synchronized List<SqlExecution> recent() {
        List<SqlExecution> snapshot = new ArrayList<>(ring);
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }
}
