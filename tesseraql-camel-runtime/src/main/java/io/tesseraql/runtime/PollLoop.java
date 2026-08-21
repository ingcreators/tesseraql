package io.tesseraql.runtime;

import io.tesseraql.opsui.PollSourceStatus;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.service.ServiceSupport;

/**
 * One poll job's cycle, run without a Camel consumer (docs/camel-removal.md slice 1).
 *
 * <p>What a file consumer does, once the endpoint URI is taken away: list the directory, keep the
 * names the {@code include:} glob admits, leave anything still being written, claim what a
 * {@code consumeOnce:} source must not import twice, run the import, and move the file to the
 * {@code move:} or {@code moveFailed:} directory according to how that went. Every rule here is
 * the one the endpoint options asked for, and the transport differences live behind
 * {@link PollSource} so that a rule cannot hold for one transport and not the other.
 *
 * <p><strong>One thread per job, and it blocks on purpose.</strong> The import is awaited inside
 * the cycle — that is what makes {@code move:} and {@code moveFailed:} mean what the documentation
 * says they mean — so a poll job processes one file at a time and the file's fate is settled
 * before the next one starts. It is a virtual thread, so the waiting costs nothing to hold.
 */
final class PollLoop extends ServiceSupport {

    /**
     * The cross-replica claim a {@code consumeOnce:} source makes before it imports.
     *
     * <p>An interface rather than the JDBC store, because what the cycle needs is the answer to
     * "is this file mine to take" — and a rule this load-bearing should be assertable without a
     * database. {@code JdbcPollConsumedStore::claim} is the implementation.
     */
    @FunctionalInterface
    interface Claim {

        /** Claims {@code fileKey} for {@code jobId}, answering false when another replica has it. */
        boolean claim(String jobId, String fileKey);
    }

    private static final System.Logger LOG = System.getLogger(PollLoop.class.getName());

    /**
     * The longest a cycle waits before re-reading a candidate's fingerprint.
     *
     * <p>This is the write-stability check {@code readLock=changed} performed: a file whose size
     * or modification time moved between the two reads is being written and is left for a later
     * cycle. Bounded by the declared poll interval, so a source that polls every 500ms does not
     * spend a second per cycle waiting.
     */
    private static final long STABILITY_CEILING_MILLIS = 1000;

    private final String jobId;
    private final PollSource source;
    private final Processor importer;
    private final CamelContext context;
    private final PathMatcher include;
    private final String move;
    private final String moveFailed;
    private final long delayMillis;
    private final Claim consumed;
    private final PollSourceStatus status;
    private final String transport;

    private volatile Thread worker;

    PollLoop(String jobId, String transport, PollSource source, Processor importer,
            CamelContext context, String include, String move, String moveFailed,
            long delayMillis, Claim consumed, PollSourceStatus status) {
        this.jobId = jobId;
        this.transport = transport;
        this.source = source;
        this.importer = importer;
        this.context = context;
        this.include = include == null || include.isBlank()
                ? null
                // A glob, matched against the name — not a fragment of a query string. An
                // include: carrying an '&' matches a file whose name carries one, which is the
                // whole of what it can now do.
                : FileSystems.getDefault().getPathMatcher("glob:" + include);
        this.move = move;
        this.moveFailed = moveFailed;
        this.delayMillis = delayMillis;
        this.consumed = consumed;
        this.status = status;
    }

    @Override
    protected void doStart() {
        worker = Thread.ofVirtual().name("tesseraql-poll-" + jobId).start(this::run);
        status.polling(jobId, transport);
        LOG.log(System.Logger.Level.INFO, "Polling {0} source for job {1}", transport, jobId);
    }

    @Override
    protected void doStop() throws Exception {
        Thread running = worker;
        if (running != null) {
            running.interrupt();
            running.join(java.time.Duration.ofSeconds(10));
            worker = null;
        }
        source.close();
    }

    private void run() {
        while (isRunAllowed() && !Thread.currentThread().isInterrupted()) {
            try {
                cycle();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                // A source that cannot be reached is retried on the next cycle, as the consumer
                // did: a partner's server going away for a minute is not this runtime's failure.
                LOG.log(System.Logger.Level.WARNING, "Poll cycle for job " + jobId + " failed: "
                        + ex.getMessage(), ex);
            }
            if (!sleep(delayMillis)) {
                return;
            }
        }
    }

    /**
     * One pass over the directory.
     *
     * <p>The stability wait is taken once for the whole cycle rather than once per file: every
     * candidate is re-read after it, so a directory with fifty files costs one wait, not fifty.
     */
    private void cycle() throws Exception {
        List<PollSource.PolledFile> candidates = new ArrayList<>();
        for (PollSource.PolledFile file : source.list()) {
            if (include == null || include.matches(Path.of(file.name()))) {
                candidates.add(file);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (!sleep(Math.min(STABILITY_CEILING_MILLIS, delayMillis))) {
            return;
        }
        for (PollSource.PolledFile file : candidates) {
            if (!isRunAllowed() || Thread.currentThread().isInterrupted()) {
                return;
            }
            Optional<PollSource.PolledFile> now = source.stat(file.name());
            if (now.isEmpty() || now.get().size() != file.size()
                    || now.get().modified() != file.modified()) {
                continue;
            }
            // Claiming before the import rather than remembering after it is what makes this
            // atomic across replicas: two nodes can both pass a "have I seen this?" check and
            // both import, and only the insert settles it.
            if (consumed != null && !consumed.claim(jobId, file.key())) {
                continue;
            }
            consume(file);
        }
    }

    private void consume(PollSource.PolledFile file) {
        PollSource.Fetched fetched = null;
        try {
            fetched = source.fetch(file);
            Exchange exchange = new DefaultExchange(context);
            exchange.getMessage().setHeader(Exchange.FILE_NAME, file.name());
            try (InputStream content = Files.newInputStream(fetched.path())) {
                exchange.getMessage().setBody(content);
                importer.process(exchange);
            }
            source.archive(file, move);
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.WARNING, "Polled file " + file.name() + " for job "
                    + jobId + " did not import: " + ex.getMessage(), ex);
            archiveFailure(file);
        } finally {
            if (fetched != null) {
                fetched.release();
            }
        }
    }

    /**
     * Moves a file whose import failed, and says so loudly when even that fails.
     *
     * <p>A file that can be neither imported nor moved is picked up again on the next cycle, which
     * is a loop an operator has to be able to see in the log.
     */
    private void archiveFailure(PollSource.PolledFile file) {
        try {
            source.archive(file, moveFailed);
        } catch (Exception ex) {
            LOG.log(System.Logger.Level.ERROR, "Polled file " + file.name() + " for job " + jobId
                    + " could not be moved to " + moveFailed + "; it will be polled again: "
                    + ex.getMessage(), ex);
        }
    }

    /** Sleeps, answering false when the loop was asked to stop while waiting. */
    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return isRunAllowed();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
