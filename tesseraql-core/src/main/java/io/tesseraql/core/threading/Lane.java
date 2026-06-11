package io.tesseraql.core.threading;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single execution lane (design ch. 24): an executor paired with an admission semaphore that
 * bounds in-flight work and provides backpressure.
 *
 * <p>Admission ({@link #tryAdmit()}/{@link #release()}) is separated from execution so that
 * callers can reject over-capacity work with a precise error before any thread is dispatched,
 * rather than relying on queue overflow. Admission and release must be balanced by the caller.
 */
public final class Lane implements AutoCloseable {

    private final LanePolicy policy;
    private final ExecutorService executor;
    private final Semaphore permits;
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();

    Lane(LanePolicy policy) {
        this.policy = policy;
        this.permits = new Semaphore(policy.maxConcurrency());
        this.executor = createExecutor(policy);
    }

    private static ExecutorService createExecutor(LanePolicy policy) {
        return switch (policy.type()) {
            case VIRTUAL -> {
                ThreadFactory factory = Thread.ofVirtual()
                        .name("tesseraql-" + policy.name() + "-", 0).factory();
                yield Executors.newThreadPerTaskExecutor(factory);
            }
            case PLATFORM -> {
                ThreadFactory factory = Thread.ofPlatform()
                        .name("tesseraql-" + policy.name() + "-", 0).factory();
                yield Executors.newFixedThreadPool(policy.maxConcurrency(), factory);
            }
        };
    }

    public String name() {
        return policy.name();
    }

    public LanePolicy policy() {
        return policy;
    }

    /** The executor that runs admitted work; bind this into the route runtime for dispatch. */
    public ExecutorService executor() {
        return executor;
    }

    /**
     * Attempts to admit one unit of work without blocking. Returns {@code true} when a permit was
     * acquired (the caller must later {@link #release()}), or {@code false} when the lane is at
     * capacity (the caller should reject the work).
     */
    public boolean tryAdmit() {
        if (permits.tryAcquire()) {
            admitted.incrementAndGet();
            return true;
        }
        rejected.incrementAndGet();
        return false;
    }

    /** Releases one previously admitted permit. */
    public void release() {
        permits.release();
    }

    /** The number of additional units that can be admitted right now. */
    public int available() {
        return permits.availablePermits();
    }

    /** Total units admitted over the lane's lifetime. */
    public long admittedCount() {
        return admitted.get();
    }

    /** Total units rejected (backpressure) over the lane's lifetime. */
    public long rejectedCount() {
        return rejected.get();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
