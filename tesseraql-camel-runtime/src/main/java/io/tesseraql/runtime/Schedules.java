package io.tesseraql.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import org.apache.camel.CamelContext;
import org.apache.camel.support.service.ServiceSupport;
import org.quartz.CronExpression;

/**
 * The runtime's periodic work, without a scheduler framework (docs/camel-removal.md structural
 * decision 5).
 *
 * <p>Eleven fixed-period sweeps and one cron. The sweeps were {@code timer:} routes, which is a
 * consumer, a route and an exchange for something that is a loop with a sleep in it; the cron was
 * a {@code quartz:} route, which is a whole scheduler for one expression.
 *
 * <p><strong>One virtual thread per schedule, and no pool to size.</strong> The same shape
 * {@link PollLoop} took, for the same reason: a pool would be another number to pick, and these
 * threads spend their lives asleep. Each schedule waits its period <em>after</em> its run
 * finishes, which is what the {@code timer:} consumer did by running the route on its own thread —
 * a slow sweep delays its own next firing and nothing else's.
 *
 * <p><strong>Quartz's cron expression stays; Quartz's scheduler goes.</strong> What makes a cron
 * firing safe across replicas is not the scheduler: it is that every node computes the
 * <em>same</em> fire time and only one of them wins the claim in {@code tql_job_claim}.
 * {@link CronExpression#getNextValidTimeAfter} is that computation, and it is deterministic given
 * the same expression and time zone — so the property survives, and what leaves is a scheduler,
 * its thread pool, its job store and the connection pool the job store brought with it.
 */
final class Schedules extends ServiceSupport {

    private static final System.Logger LOG = System.getLogger(Schedules.class.getName());

    /** Registry name, so a builder can add a schedule without being handed this. */
    static final String BEAN = "tesseraqlSchedules";

    /** How long a stop waits for a sweep that is mid-run before giving up on it. */
    private static final long STOP_WAIT_MILLIS = 10_000;

    private final List<Thread> workers = new ArrayList<>();
    private volatile boolean running = true;

    private Schedules() {
    }

    /** The runtime's schedules, created and registered as a service on first use. */
    static Schedules of(CamelContext context) {
        Schedules existing = context.getRegistry().lookupByNameAndType(BEAN, Schedules.class);
        if (existing != null) {
            return existing;
        }
        Schedules created = new Schedules();
        context.getRegistry().bind(BEAN, created);
        try {
            // As a service, so every schedule stops when the context does.
            context.addService(created);
        } catch (Exception cannotRegister) {
            throw new IllegalStateException("Could not register the runtime's schedules",
                    cannotRegister);
        }
        return created;
    }

    /**
     * Runs {@code task} every {@code periodMillis}, waiting the period after each run.
     *
     * <p>The first run happens one period in, which is what {@code delay=period} said on every
     * timer route: a sweep at boot competes with the work of starting up and finds nothing to do.
     */
    void every(String name, long periodMillis, Runnable task) {
        start(name, () -> {
            while (sleep(periodMillis)) {
                run(name, task);
            }
        });
    }

    /**
     * Runs {@code task} at each of the cron's fire times, handing it the time it was scheduled
     * for rather than the time it woke up.
     *
     * <p>The distinction is the whole mechanism: the scheduled time is identical on every node, so
     * it is what a firing is claimed under. The woken time is not.
     */
    void cron(String name, String expression, Consumer<Instant> task) {
        CronExpression cron;
        try {
            cron = new CronExpression(expression);
        } catch (java.text.ParseException invalid) {
            throw new IllegalArgumentException(
                    "Schedule '" + name + "' has an invalid cron expression '" + expression
                            + "': " + invalid.getMessage(),
                    invalid);
        }
        start(name, () -> {
            Date after = new Date();
            while (running) {
                Date next = cron.getNextValidTimeAfter(after);
                if (next == null) {
                    LOG.log(System.Logger.Level.INFO,
                            "Schedule {0} has no further firings; its cron is exhausted", name);
                    return;
                }
                long wait = next.getTime() - System.currentTimeMillis();
                if (wait > 0 && !sleep(wait)) {
                    return;
                }
                Instant scheduled = next.toInstant();
                run(name, () -> task.accept(scheduled));
                // The next firing is computed from now rather than from the time just fired, so a
                // run that outlasts its own interval resumes the schedule instead of replaying
                // every firing it missed. That is Quartz's smart misfire policy in one line, and
                // the property it protects is that a slow job does not come back as a burst.
                after = new Date();
            }
        });
    }

    private void start(String name, Runnable loop) {
        Thread worker = Thread.ofVirtual().name("tesseraql-schedule-" + name).start(loop);
        synchronized (workers) {
            workers.add(worker);
        }
    }

    /**
     * Runs one firing, and survives it failing.
     *
     * <p>A timer route sent its exception to the error handler and kept firing; a sweep that threw
     * must not take its schedule down with it, or one bad night stops a retention sweep for the
     * life of the process.
     */
    private void run(String name, Runnable task) {
        try {
            task.run();
        } catch (Exception failed) {
            LOG.log(System.Logger.Level.WARNING,
                    "Scheduled " + name + " failed: " + failed.getMessage(), failed);
        }
    }

    /** Sleeps, answering false when the runtime asked everything to stop while waiting. */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return running;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    protected void doStop() {
        running = false;
        List<Thread> stopping;
        synchronized (workers) {
            stopping = List.copyOf(workers);
            workers.clear();
        }
        stopping.forEach(Thread::interrupt);
        for (Thread worker : stopping) {
            try {
                worker.join(java.time.Duration.ofMillis(STOP_WAIT_MILLIS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
