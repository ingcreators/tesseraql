package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.connectors.PollConnectors;
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
 * {@code tesseraql.connectors.poll.allowedHosts} is refused (the same rule lint enforces). A
 * misconfigured poll job is logged and skipped rather than failing the whole runtime, so one bad
 * job never takes the app down.
 */
final class PollingRouteBuilder extends RouteBuilder {

    private static final System.Logger LOG = System
            .getLogger(PollingRouteBuilder.class.getName());

    private final List<JobFile> jobs;
    private final PollConnectors connectors;
    private final String appName;
    private final Map<String, String> jobOwners;

    PollingRouteBuilder(List<JobFile> jobs, PollConnectors connectors, String appName,
            Map<String, String> jobOwners) {
        this.jobs = List.copyOf(jobs);
        this.connectors = connectors;
        this.appName = appName;
        this.jobOwners = Map.copyOf(jobOwners);
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
            }
        }
    }

    private void wire(JobFile job, PollSpec poll) {
        String jobId = job.definition().id();
        ImportSpec importSpec = job.definition().fileImport();
        if (importSpec == null || importSpec.sql() == null || importSpec.sql().file() == null) {
            LOG.log(System.Logger.Level.ERROR,
                    "Poll job {0} has no import: block with a per-row sql; skipping", jobId);
            return;
        }
        if (poll.isRemote() && !connectors.isHostAllowed(poll.host())) {
            LOG.log(System.Logger.Level.ERROR, "Poll job {0} targets host {1} which is not in"
                    + " tesseraql.connectors.poll.allowedHosts (deny by default); skipping",
                    jobId, poll.host());
            return;
        }

        String uri = endpointUri(poll);
        Path rowSqlFile = job.source().getParent().resolve(importSpec.sql().file()).normalize();
        String owner = jobOwners.getOrDefault(jobId, appName);
        io.tesseraql.core.files.FileReadSpec readSpec = importSpec.toReadSpec()
                .withLocale(importSpec.locale());
        from(uri).routeId("poll." + jobId).process(new PollImportProcessor(
                jobId, owner, importSpec.format(), readSpec, rowSqlFile,
                importSpec.effectiveOnError()));
        LOG.log(System.Logger.Level.INFO, "Polling {0} source for job {1}",
                poll.effectiveSource(), jobId);
    }

    /** Builds the Camel consumer URI for the source, keeping the component name out of the YAML. */
    private String endpointUri(PollSpec poll) {
        String options = "delay=" + Durations.toMillis(poll.effectiveDelay())
                + "&move=" + poll.effectiveMove()
                + "&moveFailed=" + poll.effectiveMoveFailed()
                + "&readLock=changed"
                + (poll.include() == null || poll.include().isBlank()
                        ? ""
                        : "&antInclude=" + poll.include());
        return switch (poll.effectiveSource()) {
            case "local" -> "file://" + poll.path() + "?" + options;
            // The edge's SSH host key is verified out of band; production sets a knownHostsFile.
            case "sftp" -> remoteUri("sftp", poll, 22, options + "&strictHostKeyChecking=no");
            case "ftps" -> remoteUri("ftps", poll, 21,
                    options + "&disableSecureDataChannelDefaults=true");
            default -> throw new IllegalArgumentException(
                    "Unsupported poll source '" + poll.source() + "'");
        };
    }

    private String remoteUri(String scheme, PollSpec poll, int defaultPort, String options) {
        PollConnectors.Credential credential = poll.credential() == null
                ? null
                : connectors.requireCredential(poll.credential());
        int port = poll.port() == null ? defaultPort : poll.port();
        String path = poll.path().startsWith("/") ? poll.path().substring(1) : poll.path();
        StringBuilder uri = new StringBuilder(scheme).append("://")
                .append(poll.host()).append(':').append(port).append('/').append(path)
                .append('?').append(options);
        if (credential != null) {
            uri.append("&username=").append(credential.require("username"))
                    // RAW(...) keeps Camel from URL-decoding a password with reserved characters.
                    .append("&password=RAW(").append(credential.require("password")).append(')');
        }
        return uri.toString();
    }
}
