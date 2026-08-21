package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.connectors.FileConnectors;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a {@code poll:} or {@code push:} block means for FTPS, resolved before anything connects
 * (docs/camel-removal.md decision 4).
 *
 * <p>FTPS shares the recipe with SFTP but not the transport settings, and assuming the scheme was
 * the only difference is what let its data channel stay unencrypted for a year. Each of these was
 * one endpoint option long and asserted against a URI string; they are the same rules with the
 * same error codes, asserted against the settings the client is built from.
 */
class FtpsClientTest {

    @TempDir
    Path home;

    @Test
    void theServerIsValidatedAgainstTheDeclaredTrustStore() {
        FtpsClient.Settings settings = settings(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "trustStore", Map.of("file", "security/partner-ca.p12", "password", "s3cr3t")));

        // The path resolves against the app home, like other configured file paths.
        assertThat(settings.trustStore())
                .isEqualTo(home.resolve("security/partner-ca.p12").toAbsolutePath());
        assertThat(settings.trustStorePassword()).isEqualTo("s3cr3t");
    }

    /**
     * Without a trust store there is nothing to validate against.
     *
     * <p>The default trust manager checks only that a certificate is in date — no chain, no
     * anchor, no hostname — so any self-signed certificate from any host is accepted and the
     * handshake proves nothing about who answered. The job is refused instead of polled.
     */
    @Test
    void withoutATrustStoreTheSourceIsRefusedRatherThanRunUnverified() {
        Map<String, Object> withoutTrust = new LinkedHashMap<>(Map.of(
                "allowedHosts", List.of("ftps.partner.example"),
                "credentials", Map.of("partner", Map.of("username", "svc", "password", "s"))));

        assertThatThrownBy(() -> settingsFrom(withoutTrust, "partner"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trustStore");
    }

    @Test
    void aRemoteSourceWithoutACredentialIsRefused() {
        assertThatThrownBy(() -> settingsFrom(complete(Map.of()), null))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4088")
                .hasMessageContaining("needs a credential");
    }

    /** Mutual TLS: how a partner identifies <em>us</em>, alongside the login rather than instead. */
    @Test
    void aDeclaredClientCertificateAccompaniesThePassword() {
        FtpsClient.Settings settings = withCredential(Map.of("username", "svc",
                "password", "s3cr3t", "keyStoreFile", "/etc/tql/client.p12",
                "keyStorePassword", "kp", "keyStoreType", "PKCS12"));

        assertThat(settings.password()).isEqualTo("s3cr3t");
        assertThat(settings.keyStore()).isEqualTo("/etc/tql/client.p12");
        assertThat(settings.keyStorePassword()).isEqualTo("kp");
        assertThat(settings.keyStoreType()).isEqualTo("PKCS12");
    }

    @Test
    void aCredentialWithoutAKeyStoreCarriesNoClientCertificate() {
        FtpsClient.Settings settings = withCredential(
                Map.of("username", "svc", "password", "s3cr3t"));

        assertThat(settings.keyStore()).isNull();
        assertThat(settings.keyStorePassword()).isNull();
    }

    @Test
    void aPrivateKeyOnAnFtpsSourceIsRefused() {
        assertThatThrownBy(() -> withCredential(
                Map.of("username", "svc", "privateKeyFile", "/keys/id_ed25519")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SEC-4089")
                .hasMessageContaining("only an sftp endpoint");
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
        assertThat(settingsForPath("outbound/orders").directory()).isEqualTo("outbound/orders");
        assertThat(settingsForPath("/outbound/orders").directory()).isEqualTo("/outbound/orders");
        assertThat(settingsForPath("//outbound/orders").directory()).isEqualTo("/outbound/orders");
    }

    @Test
    void thePortDefaultsToTwentyOneAndADeclaredOneWins() {
        assertThat(settings(Map.of()).port()).isEqualTo(21);
        assertThat(FtpsClient.settings(connectors(complete(Map.of())), "ftps.partner.example",
                2121, "/outbound", "partner", home).port()).isEqualTo(2121);
    }

    private FtpsClient.Settings settings(Map<String, Object> push) {
        return settingsFrom(complete(push), "partner");
    }

    private FtpsClient.Settings settingsForPath(String path) {
        return FtpsClient.settings(connectors(complete(Map.of())), "ftps.partner.example", null,
                path, "partner", home);
    }

    private FtpsClient.Settings withCredential(Map<String, Object> credential) {
        Map<String, Object> block = new LinkedHashMap<>(complete(Map.of()));
        block.put("credentials", Map.of("partner", credential));
        return settingsFrom(block, "partner");
    }

    private FtpsClient.Settings settingsFrom(Map<String, Object> block, String credential) {
        return FtpsClient.settings(connectors(block), "ftps.partner.example", null, "/outbound",
                credential, home);
    }

    /** The credential and trust store every remote target needs, unless a case spells its own. */
    private static Map<String, Object> complete(Map<String, Object> block) {
        Map<String, Object> full = new LinkedHashMap<>(block);
        full.putIfAbsent("allowedHosts", List.of("ftps.partner.example"));
        full.putIfAbsent("credentials",
                Map.of("partner", Map.of("username", "svc", "password", "s3cr3t")));
        full.putIfAbsent("trustStore",
                Map.of("file", "security/partner-ca.p12", "password", "s3cr3t"));
        return full;
    }

    private static FileConnectors connectors(Map<String, Object> block) {
        return FileConnectors.poll(new AppConfig(
                Map.of("tesseraql", Map.of("connectors", Map.of("poll", block))), name -> null));
    }
}
