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

    private final List<JobFile> jobs;
    private final FileConnectors connectors;
    private final String appName;
    private final Map<String, String> jobOwners;
    private final Path appHome;
    private final Path workHome;
    private final io.tesseraql.opsui.PollSourceStatus status;

    PollingRouteBuilder(List<JobFile> jobs, FileConnectors connectors, String appName,
            Map<String, String> jobOwners, Path appHome, Path workHome,
            io.tesseraql.opsui.PollSourceStatus status) {
        this.jobs = List.copyOf(jobs);
        this.connectors = connectors;
        this.appName = appName;
        this.jobOwners = Map.copyOf(jobOwners);
        this.appHome = appHome;
        this.workHome = workHome;
        this.status = status;
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
                status.skipped(job.definition().id(), trigger.poll().effectiveSource(),
                        ex.getMessage());
            }
        }
    }

    private void wire(JobFile job, PollSpec poll) {
        String jobId = job.definition().id();
        ImportSpec importSpec = job.definition().fileImport();
        if (importSpec == null || importSpec.sql() == null || importSpec.sql().file() == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "Poll job {0} has no import: block with a per-row sql; skipping", jobId);
            status.skipped(jobId, poll.effectiveSource(),
                    "no import: block with a per-row sql");
            return;
        }
        if (poll.isRemote() && !connectors.isHostAllowed(poll.host())) {
            LOG.log(System.Logger.Level.ERROR, "Poll job {0} targets host {1} which is not in"
                    + " tesseraql.connectors.poll.allowedHosts (deny by default); skipping",
                    jobId, poll.host());
            status.skipped(jobId, poll.effectiveSource(), "host '" + poll.host()
                    + "' is not in tesseraql.connectors.poll.allowedHosts (deny by default)");
            return;
        }

        String uri = endpointUri(poll);
        Path rowSqlFile = job.source().getParent().resolve(importSpec.sql().file()).normalize();
        String owner = jobOwners.getOrDefault(jobId, appName);
        io.tesseraql.core.files.FileReadSpec readSpec = importSpec.toReadSpec()
                .withLocale(importSpec.locale());
        from(uri).routeId("poll." + jobId).process(new PollImportProcessor(
                jobId, owner, importSpec.format(), readSpec, rowSqlFile,
                importSpec.effectiveOnError(), status));
        status.polling(jobId, poll.effectiveSource());
        LOG.log(System.Logger.Level.INFO, "Polling {0} source for job {1}",
                poll.effectiveSource(), jobId);
    }

    /** Builds the Camel consumer URI for the source, keeping the component name out of the YAML. */
    String endpointUri(PollSpec poll) {
        String options = "delay=" + Durations.toMillis(poll.effectiveDelay())
                + "&move=" + archiveDirectory("move", poll.effectiveMove())
                + "&moveFailed=" + archiveDirectory("moveFailed", poll.effectiveMoveFailed())
                + "&readLock=changed"
                + (poll.include() == null || poll.include().isBlank()
                        ? ""
                        // RAW keeps an '&' in a glob from splitting the query and binding
                        // whatever follows as extra consumer options.
                        : "&antInclude=RAW(" + poll.include() + ")");
        return switch (poll.effectiveSource()) {
            case "local" -> "file://"
                    + connectors.requireAllowedPath(appHome, poll.path()) + "?" + options;
            case "sftp" -> RemoteFileUris.remoteUri("sftp", connectors, poll.host(),
                    poll.port(), 22, poll.path(), poll.credential(),
                    options + remoteStreamingOptions()
                            + RemoteFileUris.sftpHostKeyOptions(connectors, appHome));
            case "ftps" -> RemoteFileUris.remoteUri("ftps", connectors, poll.host(),
                    poll.port(), 21, poll.path(), poll.credential(),
                    options + remoteStreamingOptions()
                            + RemoteFileUris.ftpsTransportOptions(connectors, appHome,
                                    poll.host()));
            default -> throw new IllegalArgumentException(
                    "Unsupported poll source '" + poll.source() + "'");
        };
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
