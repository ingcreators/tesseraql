package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.connectors.FileConnectors;
import io.tesseraql.yaml.model.PollSpec;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * How a {@code poll:} trigger is wired: where a local source is allowed to point, and the
 * consumer URI for the one transport still served by a Camel consumer.
 *
 * <p>FTPS shares the recipe but not the transport settings — assuming the scheme was the only
 * difference is what let its data channel stay unencrypted for a year — so each of those settings
 * is one option long and pinned here. Local and SFTP no longer have a URI at all
 * (docs/camel-removal.md slice 1); their rules are asserted in {@link SftpPollSourceTest} and
 * {@link PollLoopTest}.
 */
class PollingRouteBuilderTest {

    @TempDir
    Path home;

    // --- where a local source may point -------------------------------------------------------

    @Test
    void aLocalPathIsAnchoredUnderADeclaredRoot() {
        PollSource source = builder(Map.of("allowedPaths", List.of("inbox")))
                .sourceFor("poll.job", local("inbox/orders"));

        assertThat(source).isInstanceOf(LocalPollSource.class);
        assertThat(((LocalPollSource) source).directory())
                .isEqualTo(home.resolve("inbox/orders").toAbsolutePath());
    }

    @Test
    void aLocalPathThatClimbsOutOfEveryRootIsRefused() {
        PollingRouteBuilder builder = builder(Map.of("allowedPaths", List.of("inbox")));

        // A poll source does not only read: it moves what it reads, so a path that escapes the
        // root relocates a live directory's contents into .done.
        assertThatThrownBy(() -> builder.sourceFor("poll.job", local("inbox/../../secret")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("outside every");
    }

    @Test
    void aLocalSourceWithNoDeclaredRootIsRefused() {
        PollingRouteBuilder builder = builder(Map.of());

        assertThatThrownBy(() -> builder.sourceFor("poll.job", local("anywhere")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("allowedPaths");
    }

    @Test
    void anUnknownTransportIsRefusedRatherThanTreatedAsLocal() {
        PollingRouteBuilder builder = builder(Map.of("allowedPaths", List.of("inbox")));
        PollSpec unknown = new PollSpec("webdav", null, null, "inbox", null, null, null, null,
                null, null);

        assertThatThrownBy(() -> builder.sourceFor("poll.job", unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported poll transport");
    }

    // --- the FTPS consumer URI ----------------------------------------------------------------

    @Test
    void ftpsEncryptsTheDataChannelAndTransfersBytesVerbatim() {
        String uri = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "trustStore", Map.of("file", "security/partner-ca.p12", "password", "s3cr3t")))
                .endpointUri("poll.job", ftps());

        assertThat(uri)
                .startsWith("ftps://ftps.partner.example:21//outbound?")
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
        PollingRouteBuilder builder = builderFrom(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "credentials", Map.of("partner", Map.of("username", "svc", "password", "s"))));

        // Nothing to validate against means any in-date certificate from any host is accepted,
        // so the handshake proves nothing about the peer. Refuse the job instead of polling it.
        assertThatThrownBy(() -> builder.endpointUri("poll.job", ftps()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStore");
    }

    /**
     * A remote source with no credential is refused rather than polled anonymously.
     *
     * <p>It was accepted, and produced a URI with no username and no password — and FTPS may well
     * succeed as anonymous, a poll job quietly reading whatever an anonymous session can see.
     */
    @Test
    void aRemoteSourceWithoutACredentialIsRefused() {
        PollingRouteBuilder builder = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example")));
        PollSpec anonymous = new PollSpec("ftps", "ftps.partner.example", null, "/outbound",
                null, null, null, null, null, null);

        assertThatThrownBy(() -> builder.endpointUri("poll.job", anonymous))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4088")
                .hasMessageContaining("needs a credential");
    }

    /**
     * FTPS can present a client certificate alongside its password.
     *
     * <p>Mutual TLS is how a partner identifies <em>us</em>, and it was unreachable: the trust
     * store proved who answered and nothing carried a certificate the other way.
     */
    @Test
    void ftpsPresentsADeclaredClientCertificate() {
        String uri = builderWith(Map.of("username", "svc", "password", "s3cr3t",
                "keyStoreFile", "/etc/tql/client.p12", "keyStorePassword", "kp"))
                .endpointUri("poll.job", ftps());

        assertThat(uri).contains("ftpClient.keyStore.file=/etc/tql/client.p12");
        assertThat(uri).contains("ftpClient.keyStore.password=RAW(kp)");
        // The login is a separate question the server may also ask.
        assertThat(uri).contains("password=RAW(s3cr3t)");
    }

    @Test
    void aCredentialWithoutAKeyStoreCarriesNoClientCertificate() {
        String uri = builderWith(Map.of("username", "svc", "password", "s3cr3t"))
                .endpointUri("poll.job", ftps());

        assertThat(uri).doesNotContain("keyStore");
    }

    @Test
    void declaringBothAPasswordAndAKeyIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc", "password", "s3cr3t",
                "privateKeyFile", "/keys/id_ed25519"));

        // Which one wins is exactly the question a deployment should never answer by experiment.
        assertThatThrownBy(() -> builder.endpointUri("poll.job", ftps()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4089")
                .hasMessageContaining("both");
    }

    @Test
    void aCredentialWithNoMethodIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc"));

        assertThatThrownBy(() -> builder.endpointUri("poll.job", ftps()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("neither");
    }

    @Test
    void aPrivateKeyOnAnFtpsSourceIsRefused() {
        PollingRouteBuilder builder = builderWith(Map.of("username", "svc",
                "privateKeyFile", "/keys/id_ed25519"));

        assertThatThrownBy(() -> builder.endpointUri("poll.job", ftps()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("only an sftp endpoint");
    }

    @Test
    void aRemotePathMeansWhatItSays() {
        // The declared path passes through (contract-bugfixes track C): no slash is
        // login-home-relative, a leading slash is absolute, and the historical `//` escape
        // collapses to the same absolute meaning instead of breaking.
        PollingRouteBuilder builder = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example")));
        assertThat(builder.endpointUri("poll.job", ftps("outbound/orders")))
                .startsWith("ftps://ftps.partner.example:21/outbound/orders?");
        assertThat(builder.endpointUri("poll.job", ftps("/outbound/orders")))
                .startsWith("ftps://ftps.partner.example:21//outbound/orders?");
        assertThat(builder.endpointUri("poll.job", ftps("//outbound/orders")))
                .startsWith("ftps://ftps.partner.example:21//outbound/orders?");
    }

    @Test
    void aRemoteSourceStreamsThroughALocalWorkDirectory() {
        String uri = builder(Map.of("allowedHosts", List.of("ftps.partner.example")))
                .endpointUri("poll.job", ftps());

        // The component otherwise loads the whole file into memory before the route sees it,
        // which is what PollImportProcessor's "never materializes in memory" comment claims does
        // not happen. The runtime's own SFTP source keeps the same promise by downloading.
        assertThat(uri).contains("localWorkDirectory="
                + home.resolve("work/poll").toAbsolutePath());
    }

    @Test
    void anArchiveDirectoryThatIsAnExpressionOrAPathIsRefused() {
        PollingRouteBuilder builder = builder(Map.of(
                "allowedHosts", List.of("ftps.partner.example")));

        // Camel evaluates move: as a Simple expression, so this writes the polled file outside
        // the poll tree entirely — escaping would not help, only refusing the value does.
        assertThatThrownBy(() -> builder.endpointUri("poll.job",
                new PollSpec("ftps", "ftps.partner.example", null, "/outbound", "partner",
                        null, null, "${file:parent}/../../escaped", null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Simple expression");
        assertThatThrownBy(() -> builder.endpointUri("poll.job",
                new PollSpec("ftps", "ftps.partner.example", null, "/outbound", "partner",
                        null, null, null, "../outside", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relative directory name");
    }

    // --- exclusive consumption (docs/audit-hardening.md Decision 4) -----------------------------

    /**
     * The two options that decide it, and the key that makes it safe.
     *
     * <p>{@code idempotentEager=true} is not decoration: {@code GenericFileConsumer} branches on
     * it, and the lazy default calls {@code contains} then adds on completion — check-then-act, so
     * two replicas can both pass and both import. And the key is name-size-modified rather than
     * Camel's default absolute path, or a partner re-sending a file under a name it has used
     * before is suppressed forever. {@link PollSource.PolledFile#key()} is the same key on the
     * transports that no longer go through a URI.
     */
    @Test
    void consumeOnceWiresTheEagerIdempotentStoreWithACompositeKey() {
        String uri = builder(Map.of("allowedHosts", List.of("ftps.partner.example")))
                .endpointUri("orders.intake", consumeOnce(ftps()));

        assertThat(uri)
                .contains("&idempotent=true")
                .contains("&idempotentEager=true")
                .contains("idempotentKey=RAW(${file:name}-${file:size}-${file:modified})")
                .contains("idempotentRepository=#bean:tesseraqlPollConsumed-orders.intake")
                // Kept alongside, not replaced: the read lock is the write-stability check.
                .contains("readLock=changed")
                // readLock=idempotent is advertised in the component catalogue and unimplemented in
                // the remote strategy factory, where it silently yields no lock at all.
                .doesNotContain("readLock=idempotent");
    }

    /** Off by default, so an app that never declares it is wired exactly as before. */
    @Test
    void withoutConsumeOnceNoIdempotentOptionsAreEmitted() {
        String uri = builder(Map.of("allowedHosts", List.of("ftps.partner.example")))
                .endpointUri("orders.intake", ftps());

        assertThat(uri).doesNotContain("idempotent").contains("readLock=changed");
    }

    /** A builder whose single credential is spelled by the caller. */
    private PollingRouteBuilder builderWith(Map<String, Object> credential) {
        Map<String, Object> poll = new LinkedHashMap<>();
        poll.put("allowedHosts", List.of("sftp.partner.example", "ftps.partner.example"));
        poll.put("trustStore", Map.of("file", "/etc/tql/partner.p12", "password", "p"));
        poll.put("credentials", Map.of("partner", credential));
        return builderFrom(poll);
    }

    /** A builder with the credential and trust store every remote source needs already declared. */
    private PollingRouteBuilder builder(Map<String, Object> poll) {
        Map<String, Object> complete = new LinkedHashMap<>(poll);
        complete.putIfAbsent("credentials",
                Map.of("partner", Map.of("username", "svc", "password", "s3cr3t")));
        complete.putIfAbsent("trustStore",
                Map.of("file", "security/partner-ca.p12", "password", "s3cr3t"));
        return builderFrom(complete);
    }

    private PollingRouteBuilder builderFrom(Map<String, Object> poll) {
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", poll))), name -> null);
        return new PollingRouteBuilder(List.of(), FileConnectors.poll(config), "app", Map.of(),
                home, home.resolve("work"), new io.tesseraql.opsui.PollSourceStatus(),
                // Wiring is decided from the declaration; nothing touches the datasource until a
                // source that declares consumeOnce actually wires.
                new io.tesseraql.operations.poll.JdbcPollConsumedStore(null,
                        java.time.Duration.ofDays(30)));
    }

    private static PollSpec consumeOnce(PollSpec spec) {
        return new PollSpec(spec.transport(), spec.host(), spec.port(), spec.path(),
                spec.credential(), spec.include(), spec.delay(), spec.move(), spec.moveFailed(),
                true);
    }

    private static PollSpec local(String path) {
        return new PollSpec("local", null, null, path, null, null, null, null, null, null);
    }

    private static PollSpec ftps() {
        return ftps("/outbound");
    }

    private static PollSpec ftps(String path) {
        return new PollSpec("ftps", "ftps.partner.example", null, path, "partner", null,
                null, null, null, null);
    }
}
