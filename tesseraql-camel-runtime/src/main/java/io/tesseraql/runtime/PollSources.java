package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.connectors.FileConnectors;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.ImportSpec;
import io.tesseraql.yaml.model.PollSpec;
import io.tesseraql.yaml.model.TriggerSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.CamelContext;

/**
 * Wires the directory-polling consumers for {@code poll:}-triggered file-import jobs (roadmap
 * Phase 26): a local directory, or a remote SFTP/FTPS server, whose files flow into the job's
 * {@code import:} pipeline. The underlying Camel {@code file}/{@code sftp}/{@code ftps} endpoint
 * is an implementation detail — the user declares a poll recipe, not an endpoint URI.
 *
 * <p>Egress is deny-by-default: a remote source whose host is not in
 * {@code tesseraql.connectors.poll.allowedHosts} is refused (the same rule lint enforces). With
 * {@code tesseraql.connectors.poll.knownHostsFile} set, an SFTP source additionally verifies the
 * server's SSH host key against that known-hosts file (strict checking). A misconfigured poll job
 * is logged and skipped rather than failing the whole runtime, so one bad job never takes the app
 * down.
 */
final class PollSources {

    private static final System.Logger LOG = System
            .getLogger(PollSources.class.getName());

    private final List<JobFile> jobs;
    private final FileConnectors connectors;
    private final String appName;
    private final Map<String, String> jobOwners;
    private final Path appHome;
    private final Path workHome;
    private final io.tesseraql.opsui.PollSourceStatus status;
    /**
     * The exclusive-consumption store, consulted only by sources that declare {@code consumeOnce}.
     *
     * <p>Always supplied. The schema is created lazily in {@link #wire}, so an app with no such
     * source never touches the database for it.
     */
    private final io.tesseraql.operations.poll.JdbcPollConsumedStore consumedStore;

    PollSources(List<JobFile> jobs, FileConnectors connectors, String appName,
            Map<String, String> jobOwners, Path appHome, Path workHome,
            io.tesseraql.opsui.PollSourceStatus status,
            io.tesseraql.operations.poll.JdbcPollConsumedStore consumedStore) {
        this.jobs = List.copyOf(jobs);
        this.connectors = connectors;
        this.appName = appName;
        this.jobOwners = Map.copyOf(jobOwners);
        this.appHome = appHome;
        this.workHome = workHome;
        this.status = status;
        this.consumedStore = consumedStore;
    }

    /** Starts a poll cycle for every job that declares one. */
    void install(CamelContext context) {
        for (JobFile job : jobs) {
            TriggerSpec trigger = job.definition().trigger();
            if (trigger == null || trigger.poll() == null) {
                continue;
            }
            try {
                wire(context, job, trigger.poll());
            } catch (RuntimeException ex) {
                LOG.log(System.Logger.Level.ERROR, "Poll job {0} not wired: {1}",
                        job.definition().id(), ex.getMessage());
                // The registry is what makes the skip visible beyond this log line
                // (docs/poll-source-status.md).
                status.skipped(job.definition().id(), trigger.poll().effectiveTransport(),
                        ex.getMessage());
            }
        }
    }

    private void wire(CamelContext context, JobFile job, PollSpec poll) {
        String jobId = job.definition().id();
        ImportSpec importSpec = job.definition().fileImport();
        io.tesseraql.yaml.model.Binding rowStep = job.definition().rowStep();
        if (importSpec == null || rowStep == null || rowStep.file() == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "Poll job {0} has no import: block and per-row step; skipping", jobId);
            status.skipped(jobId, poll.effectiveTransport(),
                    "no import: block and per-row step");
            return;
        }
        if (poll.isRemote() && !connectors.isHostAllowed(poll.host())) {
            LOG.log(System.Logger.Level.ERROR, "Poll job {0} targets host {1} which is not in"
                    + " tesseraql.connectors.poll.allowedHosts (deny by default); skipping",
                    jobId, poll.host());
            status.skipped(jobId, poll.effectiveTransport(), "host '" + poll.host()
                    + "' is not in tesseraql.connectors.poll.allowedHosts (deny by default)");
            return;
        }

        String transport = poll.effectiveTransport();
        // Validated for both paths, before either is built: the constraint is on what a
        // move: may name, and it does not become weaker because one transport no longer
        // spells it into a URI.
        String move = archiveDirectory("move", poll.effectiveMove());
        String moveFailed = archiveDirectory("moveFailed", poll.effectiveMoveFailed());
        if (poll.consumesOnce()) {
            consumedStore.ensureSchema();
        }
        Path rowSqlFile = job.source().getParent().resolve(rowStep.file()).normalize();
        String owner = jobOwners.getOrDefault(jobId, appName);
        io.tesseraql.core.files.FileReadSpec readSpec = importSpec.toReadSpec()
                .withLocale(importSpec.locale());
        PollImportProcessor importer = new PollImportProcessor(jobId, owner,
                importSpec.format(), readSpec, rowSqlFile, importSpec.effectiveOnError(), status);
        // Started and stopped with the context, the same lifecycle a consumer had
        // (docs/camel-removal.md slice 1). The loop reports itself as polling when it starts.
        PollLoop loop = new PollLoop(jobId, transport, sourceFor(jobId, poll), importer,
                context, poll.include(), move, moveFailed,
                Durations.toMillis(poll.effectiveDelay()),
                poll.consumesOnce() ? consumedStore::claim : null, status);
        try {
            context.addService(loop);
        } catch (Exception ex) {
            // Surfaced as the wiring failure it is, so this job is skipped and recorded like any
            // other bad poll declaration rather than taking the whole app down.
            throw new IllegalStateException("Poll loop for job " + jobId + " did not start: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * The directory this job polls, resolved and validated the way its transport requires.
     *
     * <p>Package-private because the anchoring and credential rules are the part worth pinning,
     * and they are decided here.
     */
    PollSource sourceFor(String jobId, PollSpec poll) {
        Path work = workHome.resolve("poll").resolve(jobId);
        return switch (poll.effectiveTransport()) {
            case "sftp" -> new RemotePollSource(new SftpClient(SftpClient.settings(connectors,
                    poll.host(), poll.port(), poll.path(), poll.credential(), appHome)), work);
            case "ftps" -> new RemotePollSource(new FtpsClient(FtpsClient.settings(connectors,
                    poll.host(), poll.port(), poll.path(), poll.credential(), appHome)), work);
            case "local" -> new LocalPollSource(
                    connectors.requireAllowedPath(appHome, poll.path()));
            default -> throw new IllegalArgumentException(
                    "Unsupported poll transport '" + poll.transport() + "'");
        };
    }

    /**
     * Validates an archive directory ({@code move:} / {@code moveFailed:}).
     *
     * <p>A path or a placeholder here would relocate the polled file outside the poll tree
     * entirely — an arbitrary-destination write of its contents from a plain YAML scalar. The
     * value is constrained to a relative directory name, which is the contract
     * docs/connectors.md publishes.
     */
    static String archiveDirectory(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Poll " + key + " must name a directory");
        }
        boolean unsafe = value.contains("${") || value.contains("..")
                || value.startsWith("/") || value.contains("&") || value.contains("?")
                || value.contains("\\");
        if (unsafe) {
            throw new IllegalArgumentException("Poll " + key + " '" + value + "' must be a plain"
                    + " relative directory name: a path or a placeholder can write the polled"
                    + " file anywhere");
        }
        return value;
    }

}
