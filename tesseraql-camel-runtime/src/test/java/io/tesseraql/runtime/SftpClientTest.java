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
 * What a {@code poll:} or {@code push:} block means for SFTP, resolved before anything
 * connects (docs/camel-removal.md slice 1, decision 4).
 *
 * <p>These rules were asserted against an endpoint URI until the poll cycle stopped building one.
 * They are the same rules, with the same error codes, asserted against the settings the client is
 * given instead — which is the point of computing them apart from the connection: a security rule
 * nobody can check without a server is a security rule nobody checks.
 */
class SftpClientTest {

    @TempDir
    Path home;

    @Test
    void aConfiguredKnownHostsFileMakesTheServerIdentityChecked() {
        SftpClient.Settings settings = settings(Map.of(
                "allowedHosts", List.of("sftp.partner.example"),
                "knownHostsFile", "security/known_hosts"), sftp());

        // The path resolves against the app home, like other configured file paths.
        assertThat(settings.knownHostsFile())
                .isEqualTo(home.resolve("security/known_hosts").toAbsolutePath());
        assertThat(settings.strictHostKeyChecking()).isTrue();
    }

    @Test
    void anAbsoluteKnownHostsFilePassesThroughUnchanged() {
        Path pinned = home.resolve("etc/ssh/known_hosts").toAbsolutePath();

        SftpClient.Settings settings = settings(Map.of("knownHostsFile", pinned.toString()),
                sftp());

        assertThat(settings.knownHostsFile()).isEqualTo(pinned);
        assertThat(settings.strictHostKeyChecking()).isTrue();
    }

    /** Without one there is nothing to check against, and existing apps keep polling. */
    @Test
    void withoutAKnownHostsFileTheHostKeyStaysUnchecked() {
        SftpClient.Settings settings = settings(
                Map.of("allowedHosts", List.of("sftp.partner.example")), sftp());

        assertThat(settings.knownHostsFile()).isNull();
        assertThat(settings.strictHostKeyChecking()).isFalse();
    }

    /**
     * A remote source with no credential is refused rather than polled anonymously.
     *
     * <p>It was accepted once, and produced a URI with no username and no password: SFTP then
     * failed at connect with a message about the server, which names the wrong thing.
     */
    @Test
    void aRemoteSourceWithoutACredentialIsRefused() {
        PollSpec anonymous = new PollSpec("sftp", "sftp.partner.example", null, "/outbound",
                null, null, null, null, null, null);

        assertThatThrownBy(() -> settings(Map.of(), anonymous))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4088")
                .hasMessageContaining("needs a credential");
    }

    @Test
    void aDeclaredPrivateKeyAuthenticatesInsteadOfAPassword() {
        SftpClient.Settings settings = withCredential(Map.of("username", "svc",
                "privateKeyFile", "/keys/id_ed25519", "privateKeyPassphrase", "pp"));

        assertThat(settings.username()).isEqualTo("svc");
        assertThat(settings.privateKeyFile()).isEqualTo("/keys/id_ed25519");
        assertThat(settings.privateKeyPassphrase()).isEqualTo("pp");
        assertThat(settings.password()).isNull();
    }

    @Test
    void declaringBothAPasswordAndAKeyIsRefused() {
        // Which one wins is exactly the question a deployment should never answer by experiment.
        assertThatThrownBy(() -> withCredential(Map.of("username", "svc", "password", "s3cr3t",
                "privateKeyFile", "/keys/id_ed25519")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4089")
                .hasMessageContaining("both");
    }

    @Test
    void aCredentialWithNoMethodIsRefused() {
        assertThatThrownBy(() -> withCredential(Map.of("username", "svc")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4089")
                .hasMessageContaining("neither");
    }

    /**
     * The declared path passes through (docs/contract-bugfixes.md track C): no leading slash is
     * login-home-relative, a leading slash is absolute, and the historical {@code //} escape —
     * which existed to survive Camel's URI grammar — collapses to the same absolute meaning.
     */
    @Test
    void aRemotePathMeansWhatItSays() {
        assertThat(settings(Map.of(), sftp("outbound/orders")).directory())
                .isEqualTo("outbound/orders");
        assertThat(settings(Map.of(), sftp("/outbound/orders")).directory())
                .isEqualTo("/outbound/orders");
        assertThat(settings(Map.of(), sftp("//outbound/orders")).directory())
                .isEqualTo("/outbound/orders");
    }

    @Test
    void thePortDefaultsToTwentyTwoAndADeclaredOneWins() {
        assertThat(settings(Map.of(), sftp()).port()).isEqualTo(22);

        PollSpec onAnotherPort = new PollSpec("sftp", "sftp.partner.example", 2222, "/outbound",
                "partner", null, null, null, null, null);
        assertThat(settings(Map.of(), onAnotherPort).port()).isEqualTo(2222);
    }

    private SftpClient.Settings settings(Map<String, Object> poll, PollSpec spec) {
        Map<String, Object> withCredential = new LinkedHashMap<>(poll);
        withCredential.putIfAbsent("credentials",
                Map.of("partner", Map.of("username", "svc", "password", "s3cr3t")));
        AppConfig config = new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", withCredential))),
                name -> null);
        return SftpClient.settings(FileConnectors.poll(config), spec.host(), spec.port(),
                spec.path(), spec.credential(), home);
    }

    private SftpClient.Settings withCredential(Map<String, Object> credential) {
        return settings(new LinkedHashMap<>(Map.of("credentials", Map.of("partner", credential))),
                sftp());
    }

    private static PollSpec sftp() {
        return sftp("/outbound");
    }

    private static PollSpec sftp(String path) {
        return new PollSpec("sftp", "sftp.partner.example", null, path, "partner", null,
                null, null, null, null);
    }
}
