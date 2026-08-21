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
import org.apache.camel.builder.RouteBuilder;

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
final class PollingRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System
            .getLogger(PollingRouteBuilder.class.getName());

    /**
     * The transports still served by a Camel consumer.
     *
     * <p>{@code local} and {@code sftp} moved to the runtime's own poll cycle in
     * docs/camel-removal.md slice 1; FTPS follows in slice 5, once the transport settings its own
     * integration test pins — {@code PBSZ 0}/{@code PROT P}, the trust store, passive binary mode
     * — have a home outside an endpoint URI.
     */
    private static final java.util.Set<String> CAMEL_TRANSPORTS = java.util.Set.of("ftps");

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

    PollingRouteBuilder(List<JobFile> jobs, FileConnectors connectors, String appName,
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

    @Override
    public void configure() {
        for (JobFile job : jobs) {
            TriggerSpec trigger = job.definition().trigger();
            if (trigger == null || trigger.poll() == null) {
                continue;
            }
            try {
                wire(job, trigger.poll());
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

    private void wire(JobFile job, PollSpec poll) {
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
            if (CAMEL_TRANSPORTS.contains(transport)) {
                getContext().getRegistry().bind(repositoryBeanName(jobId),
                        new PollConsumedRepository(consumedStore, jobId));
            }
        }
        Path rowSqlFile = job.source().getParent().resolve(rowStep.file()).normalize();
        String owner = jobOwners.getOrDefault(jobId, appName);
        io.tesseraql.core.files.FileReadSpec readSpec = importSpec.toReadSpec()
                .withLocale(importSpec.locale());
        PollImportProcessor importer = new PollImportProcessor(jobId, owner,
                importSpec.format(), readSpec, rowSqlFile, importSpec.effectiveOnError(), status);
        if (CAMEL_TRANSPORTS.contains(transport)) {
            from(endpointUri(jobId, poll)).routeId("poll." + jobId).process(importer);
            status.polling(jobId, transport);
            LOG.log(System.Logger.Level.INFO, "Polling {0} source for job {1}", transport, jobId);
            return;
        }
        // Started and stopped with the context, the same lifecycle a consumer had
        // (docs/camel-removal.md slice 1). The loop reports itself as polling when it starts.
        PollLoop loop = new PollLoop(jobId, transport, sourceFor(jobId, poll), importer,
                getContext(), poll.include(), move, moveFailed,
                Durations.toMillis(poll.effectiveDelay()),
                poll.consumesOnce() ? consumedStore : null, status);
        try {
            getContext().addService(loop);
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
     * <p>Package-private for the same reason {@link #endpointUri} is: the anchoring and
     * credential rules are the part worth pinning, and they are decided here.
     */
    PollSource sourceFor(String jobId, PollSpec poll) {
        return switch (poll.effectiveTransport()) {
            case "sftp" -> new SftpPollSource(SftpPollSource.settings(connectors, poll, appHome),
                    workHome.resolve("poll").resolve(jobId));
            case "local" -> new LocalPollSource(
                    connectors.requireAllowedPath(appHome, poll.path()));
            default -> throw new IllegalArgumentException(
                    "Unsupported poll transport '" + poll.transport() + "'");
        };
    }

    /**
     * Builds the Camel consumer URI for the transports that still have one.
     *
     * <p>{@code local} and {@code sftp} are served by {@link PollLoop} over
     * {@link PollSource} (docs/camel-removal.md slice 1); FTPS joins them in slice 5, and until
     * it does this is the whole of what an endpoint URI is still assembled for.
     */
    String endpointUri(String jobId, PollSpec poll) {
        String options = "delay=" + Durations.toMillis(poll.effectiveDelay())
                + "&move=" + archiveDirectory("move", poll.effectiveMove())
                + "&moveFailed=" + archiveDirectory("moveFailed", poll.effectiveMoveFailed())
                // Kept alongside the idempotent store rather than replaced by it: readLock=changed
                // is the write-stability check that stops a half-written file being read, which is
                // a different job from deciding which replica gets it.
                + "&readLock=changed"
                + exclusiveConsumption(jobId, poll)
                + (poll.include() == null || poll.include().isBlank()
                        ? ""
                        // RAW keeps an '&' in a glob from splitting the query and binding
                        // whatever follows as extra consumer options.
                        : "&antInclude=RAW(" + poll.include() + ")");
        return switch (poll.effectiveTransport()) {
            case "ftps" -> RemoteFileUris.remoteUri("ftps", connectors, poll.host(),
                    poll.port(), 21, poll.path(), poll.credential(),
                    options + remoteStreamingOptions()
                            + RemoteFileUris.ftpsTransportOptions(connectors, appHome,
                                    poll.host()));
            default -> throw new IllegalArgumentException(
                    "Unsupported poll transport '" + poll.transport() + "'");
        };
    }

    /**
     * The consumer options that make one file the property of one replica
     * (docs/audit-hardening.md Decision 4).
     *
     * <p>Two facts decide this, and both were found in Camel's bytecode rather than its
     * documentation — the catalogue is the advertisement, the bytecode is the contract.
     *
     * <p><b>{@code readLock=idempotent} is not the mechanism.</b> {@code sftp.json} lists it in the
     * readLock enum because the option is declared on the shared endpoint configuration class, not
     * because the remote factory implements it: {@code SftpProcessStrategyFactory} handles only
     * {@code none}/{@code false}, {@code rename} and {@code changed}, and every other value falls
     * through to returning null. Setting it would leave the route with <em>no</em> read lock —
     * losing today's write-stability check and gaining nothing.
     *
     * <p><b>The consumer-level flag needs {@code idempotentEager=true}.</b>
     * {@code GenericFileConsumer} branches on {@code isIdempotentEager()}, which defaults to false.
     * The eager arm calls {@code add} and rejects a false return, which is atomic; the default arm
     * calls {@code contains} and adds on completion, which is check-then-act — two replicas can
     * both pass {@code contains} and both import the file.
     *
     * <p>The key is name, size and modified time rather than Camel's default absolute path. A
     * partner legitimately re-sending a file under a name it has used before would otherwise be
     * suppressed forever; with this key it is suppressed only while the bytes are identical and the
     * retention window has not lapsed.
     */
    private String exclusiveConsumption(String jobId, PollSpec poll) {
        if (!poll.consumesOnce()) {
            return "";
        }
        return "&idempotent=true&idempotentEager=true"
                + "&idempotentKey=RAW(${file:name}-${file:size}-${file:modified})"
                + "&idempotentRepository=#bean:" + repositoryBeanName(jobId);
    }

    /** The registry name of a source's consumption repository. */
    static String repositoryBeanName(String jobId) {
        return "tesseraqlPollConsumed-" + jobId;
    }

    /**
     * Keeps a remote file off the heap.
     *
     * <p>Both remote components default to loading the whole file into memory before the route
     * sees it — {@code streamDownload} is false and {@code localWorkDirectory} unset — so
     * {@code PollImportProcessor}'s promise that "a large file never materializes in memory" held
     * for {@code local} only, where the body is a lazy handle on a file already on disk. A
     * nightly extract of any size cost that many bytes of heap before the processor even ran, and
     * then a second copy when the transfer service spooled it.
     *
     * <p>A local work directory rather than {@code streamDownload}: the component writes the
     * remote content straight to a file, so the spool that follows is a disk-to-disk copy and the
     * consumer can still retry and move the remote file normally. It lives under the app's work
     * directory, beside every other build and runtime artifact.
     */
    private String remoteStreamingOptions() {
        return "&localWorkDirectory=" + workHome.resolve("poll").toAbsolutePath();
    }

    /**
     * Validates an archive directory ({@code move:} / {@code moveFailed:}).
     *
     * <p>Camel evaluates these as <em>Simple expressions</em>, not as plain names, so escaping is
     * not enough: {@code ${file:parent}/../../escaped/${file:onlyname}} relocates the polled file
     * outside the poll tree entirely — an arbitrary-destination write of its contents from a
     * plain YAML scalar. The component guard does not help, because Simple's {@code ${bean:…}}
     * is a language rather than a component. So the value is constrained to a relative directory
     * name instead.
     */
    private static String archiveDirectory(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Poll " + key + " must name a directory");
        }
        boolean unsafe = value.contains("${") || value.contains("..")
                || value.startsWith("/") || value.contains("&") || value.contains("?")
                || value.contains("\\");
        if (unsafe) {
            throw new IllegalArgumentException("Poll " + key + " '" + value + "' must be a plain"
                    + " relative directory name: Camel evaluates it as a Simple expression, so a"
                    + " path or placeholder can write the polled file anywhere");
        }
        return value;
    }

}
