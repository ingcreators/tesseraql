package io.tesseraql.runtime;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
 * {@code tesseraql.connectors.poll.allowedHosts} is refused (the same rule lint enforces). With
 * {@code tesseraql.connectors.poll.knownHostsFile} set, an SFTP source additionally verifies the
 * server's SSH host key against that known-hosts file (strict checking). A misconfigured poll job
 * is logged and skipped rather than failing the whole runtime, so one bad job never takes the app
 * down.
 */
final class PollingRouteBuilder extends RouteBuilder {

    /** A remote poll source declared without a credential (docs/connectors.md). */
    private static final TqlErrorCode REMOTE_NEEDS_CREDENTIAL = new TqlErrorCode(TqlDomain.SEC,
            4088);

    private static final System.Logger LOG = System
            .getLogger(PollingRouteBuilder.class.getName());

    private final List<JobFile> jobs;
    private final PollConnectors connectors;
    private final String appName;
    private final Map<String, String> jobOwners;
    private final Path appHome;
    private final Path workHome;

    PollingRouteBuilder(List<JobFile> jobs, PollConnectors connectors, String appName,
            Map<String, String> jobOwners, Path appHome, Path workHome) {
        this.jobs = List.copyOf(jobs);
        this.connectors = connectors;
        this.appName = appName;
        this.jobOwners = Map.copyOf(jobOwners);
        this.appHome = appHome;
        this.workHome = workHome;
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
            case "sftp" -> remoteUri("sftp", poll, 22,
                    options + remoteStreamingOptions() + sftpHostKeyOptions());
            case "ftps" -> remoteUri("ftps", poll, 21,
                    options + remoteStreamingOptions() + ftpsTransportOptions(poll));
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
     * Host-key verification for an SFTP source: with
     * {@code tesseraql.connectors.poll.knownHostsFile} set, the server's SSH host key must match
     * that known-hosts file (resolved against the app home, like other configured file paths);
     * without it, the key is not checked and lint nudges with {@code TQL-SEC-4084}.
     */
    private String sftpHostKeyOptions() {
        return connectors.knownHostsFile()
                .map(file -> "&knownHostsFile="
                        + appHome.resolve(file).normalize().toAbsolutePath()
                        + "&strictHostKeyChecking=yes")
                .orElse("&strictHostKeyChecking=no");
    }

    /**
     * Transport settings for an FTPS source, so it carries the same guarantees its SFTP sibling
     * does rather than only looking like it.
     *
     * <p>{@code PBSZ 0} + {@code PROT P} encrypt the <em>data</em> connection. Without them TLS
     * protects the control channel — the credentials — while every polled file's bytes cross the
     * network in cleartext, which is what the previous {@code disableSecureDataChannelDefaults}
     * produced: that option reads like hardening and does the opposite, suppressing the very
     * defaults that would have negotiated protection.
     *
     * <p>{@code binary} and {@code passiveMode} both default to false in the component. ASCII
     * mode line-ending-translates payloads in transit, so an Excel or archive import arrives
     * corrupt; active mode asks the server to open a connection back to this process, which no
     * containerized or NAT'd deployment can accept.
     */
    private String ftpsTransportOptions(PollSpec poll) {
        return "&execPbsz=0&execProt=P&binary=true&passiveMode=true" + ftpsTrustOptions(poll);
    }

    /**
     * Server-identity verification for an FTPS source, the counterpart of SFTP's known-hosts
     * check. With {@code tesseraql.connectors.poll.trustStore} declared, the server's certificate
     * chain is validated against that keystore and the hostname is checked.
     *
     * <p>Without it there is nothing to validate against: commons-net's default trust manager
     * only checks that the certificate is in date — no chain, no anchor, no hostname — so any
     * self-signed certificate from any host is accepted and TLS proves nothing about who
     * answered. The job is therefore refused rather than run unverified, and lint says so first
     * ({@code TQL-SEC-4085}).
     *
     * <p>The option names are the component's own: {@code ftpClient.trustStore.} is the
     * multi-value prefix feeding {@code FtpsEndpoint.ftpClientTrustStoreParameters}, whose
     * {@code file}/{@code password}/{@code type} keys build the trust manager, and
     * {@code ftpClient.} maps to {@code FTPSClient} bean properties —
     * {@code endpointCheckingEnabled} is what turns hostname verification on.
     */
    private String ftpsTrustOptions(PollSpec poll) {
        PollConnectors.TrustStore trust = connectors.trustStore().orElseThrow(
                () -> new IllegalArgumentException("Poll source 'ftps' for host '" + poll.host()
                        + "' needs tesseraql.connectors.poll.trustStore: without it the server"
                        + " certificate is not verified and TLS proves nothing about the peer"));
        StringBuilder options = new StringBuilder()
                .append("&ftpClient.trustStore.file=")
                .append(appHome.resolve(trust.file()).normalize().toAbsolutePath())
                .append("&ftpClient.endpointCheckingEnabled=true");
        if (trust.password() != null && !trust.password().isBlank()) {
            // RAW(...) keeps Camel from URL-decoding a password with reserved characters, the
            // same treatment the credential password gets.
            options.append("&ftpClient.trustStore.password=RAW(")
                    .append(trust.password()).append(')');
        }
        return options.toString();
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

    private String remoteUri(String scheme, PollSpec poll, int defaultPort, String options) {
        // A remote source with no credential: was accepted, and produced a URI with no username
        // and no password. SFTP then fails at connect with a message about the server, and FTPS
        // may succeed as anonymous — a poll job quietly reading whatever an anonymous session can
        // see. Neither outcome tells the operator that the declaration was incomplete.
        if (poll.credential() == null || poll.credential().isBlank()) {
            throw new TqlException(REMOTE_NEEDS_CREDENTIAL, "Poll source '" + scheme
                    + "' needs a credential: declare one under"
                    + " tesseraql.connectors.poll.credentials and reference it with credential:");
        }
        PollConnectors.Credential credential = connectors.requireCredential(poll.credential());
        int port = poll.port() == null ? defaultPort : poll.port();
        String path = poll.path().startsWith("/") ? poll.path().substring(1) : poll.path();
        StringBuilder uri = new StringBuilder(scheme).append("://")
                .append(poll.host()).append(':').append(port).append('/').append(path)
                .append('?').append(options);
        uri.append("&username=RAW(").append(credential.require("username")).append(')')
                // RAW(...) keeps Camel from URL-decoding a value with reserved characters, and
                // keeps an '&' inside one from splitting the query.
                .append("&password=RAW(").append(credential.require("password")).append(')');
        return uri.toString();
    }
}
