package io.tesseraql.operations.batch;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pulse every running execution is judged against (docs/audit-hardening.md Decision 6).
 *
 * <p>A clock, not a set of boundaries. Writing the pulse where the cooperative stop already polls —
 * step and chunk-commit boundaries — looks free and is wrong: the cadence would be bounded by step
 * duration, so a job whose long step is a single non-chunk statement emits nothing for its whole
 * runtime and a reaper reading that silence kills a live run.
 *
 * <p><strong>One thread, and one statement per tick.</strong> The tick writes every live execution
 * in one {@code update ... where job_execution_id in (...)}, so the pulse costs one connection and
 * one round trip however many executions are running. That is not a micro-optimisation: transfers
 * run on an unbounded virtual-thread executor against a ten-connection pool, so a pulse that asked
 * for a connection per execution would queue behind the very work it reports on and time out —
 * silencing every live execution at exactly the load where the reaper then kills them.
 *
 * <p>A missed tick is logged at DEBUG and is never a reason to fail the runs it reports on: the
 * liveness window is many intervals wide precisely so a transient database blip does not read as a
 * dead owner.
 */
public final class ExecutionHeartbeats implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ExecutionHeartbeats.class);

    /** How many ids one statement carries; a longer list becomes several statements. */
    private static final int CHUNK = 200;

    private final JobRepository repository;
    private final Set<String> live = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "tesseraql-execution-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * @param interval how often a running execution reports. Paired with the liveness window the
     *                 reaper reads: a window shorter than this interval would reap runs that are
     *                 alive, which lint refuses as TQL-BATCH-4211.
     */
    public ExecutionHeartbeats(JobRepository repository, Duration interval) {
        this.repository = repository;
        long millis = interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofSeconds(30).toMillis()
                : interval.toMillis();
        clock.scheduleAtFixedRate(this::tick, millis, millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts reporting for {@code executionId} until the returned handle is closed.
     *
     * <p>Closing is what stops the pulse, so the caller holds it in a try-with-resources around the
     * work: an execution that stops reporting while still running is the failure this class exists
     * to prevent, and an id left behind after the work ends would keep a finished row looking
     * alive.
     */
    public Pulse start(String executionId) {
        live.add(executionId);
        return () -> live.remove(executionId);
    }

    private void tick() {
        if (live.isEmpty()) {
            return;
        }
        List<String> ids = List.copyOf(live);
        for (int from = 0; from < ids.size(); from += CHUNK) {
            List<String> chunk = ids.subList(from, Math.min(from + CHUNK, ids.size()));
            try {
                repository.heartbeat(chunk);
            } catch (RuntimeException ex) {
                LOG.debug("Heartbeat for {} executions failed: {}", chunk.size(), ex.getMessage());
            }
        }
    }

    /** Stops the clock; the runtime calls this on shutdown, after the work has drained. */
    @Override
    public void close() {
        clock.shutdownNow();
    }

    /** A running execution's registration, closed when the work ends. */
    @FunctionalInterface
    public interface Pulse extends AutoCloseable {

        @Override
        void close();
    }
}
