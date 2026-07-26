package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.connectors.PollConnectors;
import io.tesseraql.yaml.model.PollSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The consumer-URI construction for {@code poll:} triggers: with
 * {@code tesseraql.connectors.poll.knownHostsFile} set, the SFTP endpoint verifies the server's
 * SSH host key against that file (strict checking); without it, the historical unchecked
 * behavior stays, so existing apps keep polling. The FTPS endpoint carries the transport
 * settings that make it the sibling the docs describe rather than only the scheme they share —
 * each of those is one option long, which is exactly why they need pinning.
 */
class PollingRouteBuilderTest {

    @TempDir
    Path home;

    @Test
    void sftpVerifiesTheHostKeyAgainstAConfiguredKnownHostsFile() {
        String uri = builder(Map.of(
                "allowedHosts", List.of("sftp.partner.example"),
                "knownHostsFile", "security/known_hosts")).endpointUri(sftp());

        assertThat(uri)
                .startsWith("sftp://sftp.partner.example:22/outbound?")
                // The path resolves against the app home, like other configured file paths.
                .contains("knownHostsFile="
                        + home.resolve("security/known_hosts").toAbsolutePath())
                .contains("strictHostKeyChecking=yes")
                .doesNotContain("strictHostKeyChecking=no");
    }

    @Test
    void anAbsoluteKnownHostsFilePassesThroughUnchanged() {
        Path pinned = home.resolve("etc/ssh/known_hosts").toAbsolutePath();
        String uri = builder(Map.of("knownHostsFile", pinned.toString())).endpointUri(sftp());

        assertThat(uri).contains("knownHostsFile=" + pinned)
                .contains("strictHostKeyChecking=yes");
    }

    @Test
    void withoutAKnownHostsFileTheHostKeyStaysUnchecked() {
        String uri = builder(Map.of("allowedHosts", List.of("sftp.partner.example")))
                .endpointUri(sftp());

        assertThat(uri).contains("strictHostKeyChecking=no")
                .doesNotContain("knownHostsFile=");
    }

    @Test
    void ftpsEncryptsTheDataChannelAndTransfersBytesVerbatim() {
        String uri = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "trustStore", Map.of("file", "security/partner-ca.p12", "password", "s3cr3t")))
                .endpointUri(ftps());

        assertThat(uri)
                .startsWith("ftps://ftps.partner.example:21/outbound?")
                // PBSZ/PROT protect the data connection. Without them TLS covers the control
                // channel only and every polled file crosses the network in cleartext.
                .contains("execPbsz=0")
                .contains("execProt=P")
                // The option that used to be here reads like hardening and does the opposite:
                // it suppresses the defaults that would have negotiated that protection.
                .doesNotContain("disableSecureDataChannelDefaults")
                // ASCII mode would line-ending-translate an Excel or archive payload in transit.
                .contains("binary=true")
                // Active mode asks the server to dial back into this process.
                .contains("passiveMode=true")
                // The server's certificate chain is checked against the declared trust store,
                // and the hostname against the certificate.
                .contains("ftpClient.trustStore.file="
                        + home.resolve("security/partner-ca.p12").toAbsolutePath())
                .contains("ftpClient.trustStore.password=RAW(s3cr3t)")
                .contains("ftpClient.endpointCheckingEnabled=true");
    }

    @Test
    void ftpsWithoutATrustStoreIsRefusedRatherThanRunUnverified() {
        PollingRouteBuilder builder = builder(
                Map.of("allowedHosts", List.of("ftps.partner.example")));

        // Nothing to validate against means any in-date certificate from any host is accepted,
        // so the handshake proves nothing about the peer. Refuse the job instead of polling it.
        assertThatThrownBy(() -> builder.endpointUri(ftps()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStore");
    }

    /**
     * A remote source with no credential is refused rather than polled anonymously.
     *
     * <p>It was accepted, and produced a URI with no username and no password. SFTP then fails at
     * connect with a message about the server, and FTPS may well succeed as anonymous — a poll job
     * quietly reading whatever an anonymous session can see. Neither outcome tells the operator
     * that the declaration was the incomplete part.
     */
    @Test
    void aRemoteSourceWithoutACredentialIsRefused() {
        PollingRouteBuilder builder = builder(
                Map.of("allowedHosts", List.of("sftp.partner.example")));
        PollSpec anonymous = new PollSpec("sftp", "sftp.partner.example", null, "/outbound",
                null, null, null, null, null);

        assertThatThrownBy(() -> builder.endpointUri(anonymous))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-SEC-4088")
                .hasMessageContaining("needs a credential");
    }

    /**
     * SFTP authenticates with a key when the credential declares one.
     *
     * <p>Only a password was ever emitted, so an operator who wrote {@code privateKeyFile:} got a
     * URI with no key and an error about a missing password — the failure named the wrong thing,
     * which is the worst kind of error message to debug against.
     */
    @Test
    void sftpAuthenticatesWithADeclaredPrivateKey() {
        String uri = builderWith(Map.of("username", "svc", "privateKeyFile", "/keys/id_ed25519",
                "privateKeyPassphrase", "pp")).endpointUri(sftp());

        assertThat(uri).contains("privateKeyFile=RAW(/keys/id_ed25519)");
        assertThat(uri).contains("privateKeyPassphrase=RAW(pp)");
        assertThat(uri).doesNotContain("password=");
    }

    /**
     * FTPS can present a client certificate alongside its password.
     *
     * <p>Mutual TLS is how a partner identifies <em>us</em>, and it was unreachable: the trust
     * store proved who answered and nothing carried a certificate the other way, so an FTPS
     * server requiring one could not be polled at all.
     */
    @Test
    void ftpsPresentsADeclaredClientCertificate() {
        String uri = builderWith(Map.of("username", "svc", "password", "s3cr3t",
                "keyStoreFile", "/etc/tql/client.p12", "keyStorePassword", "kp"))
                .endpointUri(ftps());

        assertThat(uri).contains("ftpClient.keyStore.file=/etc/tql/client.p12");
        assertThat(uri).contains("ftpClient.keyStore.password=RAW(kp)");
        // The login is a separate question the server may also ask.
        assertThat(uri).contains("password=RAW(s3cr3t)");
    }

    @Test
    void aCredentialWithoutAKeyStoreCarriesNoClientCertificate() {
        String uri = builderWith(Map.of("username", "svc", "password", "s3cr3t"))
                .endpointUri(ftps());

        assertThat(uri).doesNotContain("keyStore");
    }

    @Test
    void declaringBothAPasswordAndAKeyIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc", "password", "s3cr3t",
                "privateKeyFile", "/keys/id_ed25519"));

        // Which one wins is exactly the question a deployment should never answer by experiment.
        assertThatThrownBy(() -> builder.endpointUri(sftp()))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-SEC-4089")
                .hasMessageContaining("both");
    }

    @Test
    void aCredentialWithNoMethodIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc"));

        assertThatThrownBy(() -> builder.endpointUri(sftp()))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("neither");
    }

    @Test
    void aPrivateKeyOnAnFtpsSourceIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc",
                "privateKeyFile", "/keys/id_ed25519"));

        assertThatThrownBy(() -> builder.endpointUri(ftps()))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("only an sftp source");
    }

    /** A builder whose single credential is spelled by the caller. */
    private PollingRouteBuilder builderWith(Map<String, Object> credential) {
        Map<String, Object> poll = new java.util.LinkedHashMap<>();
        poll.put("allowedHosts", List.of("sftp.partner.example", "ftps.partner.example"));
        poll.put("trustStore", Map.of("file", "/etc/tql/partner.p12", "password", "p"));
        poll.put("credentials", Map.of("partner", credential));
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", poll))), name -> null);
        return new PollingRouteBuilder(List.of(), PollConnectors.load(config), "app", Map.of(),
                home, home.resolve("work"), new io.tesseraql.opsui.PollSourceStatus());
    }

    @Test
    void aLocalPathIsAnchoredUnderADeclaredRoot() {
        String uri = builder(Map.of("allowedPaths", List.of("inbox")))
                .endpointUri(local("inbox/orders"));

        assertThat(uri).startsWith("file://" + home.resolve("inbox/orders").toAbsolutePath() + "?");
    }

    @Test
    void aLocalPathThatClimbsOutOfEveryRootIsRefused() {
        PollingRouteBuilder builder = builder(Map.of("allowedPaths", List.of("inbox")));

        // The poll consumer does not only read: it moves what it reads, so a path that escapes
        // the root relocates a live directory's contents into .done.
        assertThatThrownBy(() -> builder.endpointUri(local("inbox/../../secret")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("outside every");
    }

    @Test
    void aLocalSourceWithNoDeclaredRootIsRefused() {
        PollingRouteBuilder builder = builder(Map.of());

        assertThatThrownBy(() -> builder.endpointUri(local("anywhere")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("allowedPaths");
    }

    @Test
    void anIncludeGlobCannotSmuggleExtraEndpointOptions() {
        String uri = builder(Map.of("allowedPaths", List.of("inbox")))
                .endpointUri(new PollSpec("local", null, null, "inbox", null,
                        "*.csv&noop=true", null, null, null));

        // Camel splits the query on '&' before binding, so an unwrapped glob would bind noop
        // (and anything else after it) as real consumer options.
        assertThat(uri).contains("antInclude=RAW(*.csv&noop=true)");
    }

    @Test
    void anArchiveDirectoryThatIsAnExpressionOrAPathIsRefused() {
        PollingRouteBuilder builder = builder(Map.of("allowedPaths", List.of("inbox")));

        // Camel evaluates move: as a Simple expression, so this writes the polled file outside
        // the poll tree entirely — escaping would not help, only refusing the value does.
        assertThatThrownBy(() -> builder.endpointUri(new PollSpec("local", null, null, "inbox",
                null, null, null, "${file:parent}/../../escaped", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simple expression");
        assertThatThrownBy(() -> builder.endpointUri(new PollSpec("local", null, null, "inbox",
                null, null, null, null, "../outside")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative directory name");
    }

    private PollingRouteBuilder builder(Map<String, Object> poll) {
        // Every remote source needs one now, so the fixture declares it rather than each case
        // repeating it: a remote poll with no credential is refused (TQL-SEC-4088).
        Map<String, Object> withCredential = new java.util.LinkedHashMap<>(poll);
        withCredential.putIfAbsent("credentials",
                Map.of("partner", Map.of("username", "svc", "password", "s3cr3t")));
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", withCredential))),
                name -> null);
        return new PollingRouteBuilder(List.of(), PollConnectors.load(config), "app", Map.of(),
                home, home.resolve("work"), new io.tesseraql.opsui.PollSourceStatus());
    }

    private static PollSpec sftp() {
        return new PollSpec("sftp", "sftp.partner.example", null, "/outbound", "partner", null,
                null, null, null);
    }

    @Test
    void aRemoteSourceStreamsThroughALocalWorkDirectory() {
        String sftp = builder(Map.of("allowedHosts", List.of("sftp.partner.example")))
                .endpointUri(sftp());
        String ftps = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "trustStore", Map.of("file", "ca.p12", "password", "s3cr3t")))
                .endpointUri(ftps());

        // Both remote components otherwise load the whole file into memory before the route sees
        // it, which is what PollImportProcessor's "never materializes in memory" comment claims
        // does not happen — true for local, false for sftp and ftps.
        String workDir = "localWorkDirectory=" + home.resolve("work/poll").toAbsolutePath();
        assertThat(sftp).contains(workDir);
        assertThat(ftps).contains(workDir);
    }

    private static PollSpec local(String path) {
        return new PollSpec("local", null, null, path, null, null, null, null, null);
    }

    private static PollSpec ftps() {
        return new PollSpec("ftps", "ftps.partner.example", null, "/outbound", "partner", null,
                null, null, null);
    }
}
