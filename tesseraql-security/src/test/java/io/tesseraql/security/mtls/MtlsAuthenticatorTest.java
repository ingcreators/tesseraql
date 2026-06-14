package io.tesseraql.security.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.mtls.MtlsConfig.MtlsClient;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * mTLS authentication for service callers: a forwarded X.509 client certificate is parsed, validity
 * and (optionally) PKIX checked, and its identity matched against declared clients deny-by-default.
 */
class MtlsAuthenticatorTest {

    private static final String HEADER = "ssl-client-cert";
    private static final String BILLING_DN = "CN=billing-service,O=Acme";
    private static final String BILLING_SAN = "spiffe://acme/ns/default/sa/billing";

    private static String pem(String name) throws Exception {
        try (InputStream in = MtlsAuthenticatorTest.class.getResourceAsStream("/mtls/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static MtlsClient billing(String subjectDn, String san, String sha256) {
        return new MtlsClient(subjectDn, san, sha256, "svc:billing", "tenant-a",
                List.of("SERVICE"), List.of("invoices:write"), true);
    }

    private static MtlsConfig config(String trustBundle, Map<String, MtlsClient> clients) {
        return new MtlsConfig(HEADER, trustBundle, null, clients);
    }

    /** Uppercase, colon-separated SHA-256 of the DER cert (the openssl fingerprint form). */
    private static String fingerprint(String pem) throws Exception {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(
                        new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            if (hex.length() > 0) {
                hex.append(':');
            }
            hex.append(String.format("%02X", b));
        }
        return hex.toString();
    }

    @Test
    void headerIsExposedForTheProducer() {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(config(null, Map.of()));
        assertThat(authenticator.header()).isEqualTo(HEADER);
    }

    @Test
    void matchesBySubjectDn() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(BILLING_DN, null, null))));
        Principal principal = authenticator.authenticate(pem("client.pem"));
        assertThat(principal.subject()).isEqualTo("svc:billing");
        assertThat(principal.loginId()).isEqualTo("billing-service");
        assertThat(principal.tenantId()).isEqualTo("tenant-a");
        assertThat(principal.roles()).containsExactly("SERVICE");
        assertThat(principal.permissions()).containsExactly("invoices:write");
        assertThat(principal.claim()).containsEntry("mtls_client", "billing-service");
    }

    @Test
    void matchesBySubjectAlternativeName() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(null, BILLING_SAN, null))));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    @Test
    void matchesBySha256FingerprintIgnoringColonsAndCase() throws Exception {
        String fingerprint = fingerprint(pem("client.pem")); // uppercase, colon-separated
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(null, null, fingerprint))));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    @Test
    void acceptsUrlEncodedPem() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(BILLING_DN, null, null))));
        String escaped = URLEncoder.encode(pem("client.pem"), StandardCharsets.UTF_8);
        assertThat(authenticator.authenticate(escaped).subject()).isEqualTo("svc:billing");
    }

    @Test
    void defaultsPrincipalSubjectToClientId() throws Exception {
        MtlsClient noSubject = new MtlsClient(BILLING_DN, null, null, null, null,
                List.of(), List.of(), true);
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", noSubject)));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("billing-service");
    }

    @Test
    void rejectsMissingCertificate() {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(BILLING_DN, null, null))));
        assertThatThrownBy(() -> authenticator.authenticate(null))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> authenticator.authenticate("  "))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsMalformedCertificate() {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", billing(BILLING_DN, null, null))));
        assertThatThrownBy(() -> authenticator.authenticate("not a certificate"))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsExpiredCertificate() throws Exception {
        // The matcher would accept it on identity; expiry must reject it first.
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("expired", billing("CN=expired-service,O=Acme", null, null))));
        String expired = pem("expired-client.pem");
        assertThatThrownBy(() -> authenticator.authenticate(expired))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsUnrecognizedCertificate() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null,
                        Map.of("billing-service", billing("CN=someone-else,O=Acme", null, null))));
        String client = pem("client.pem");
        assertThatThrownBy(() -> authenticator.authenticate(client))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void rejectsDisabledClient() throws Exception {
        MtlsClient disabled = new MtlsClient(BILLING_DN, null, null, "svc:billing", "tenant-a",
                List.of("SERVICE"), List.of(), false);
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, Map.of("billing-service", disabled)));
        String client = pem("client.pem");
        assertThatThrownBy(() -> authenticator.authenticate(client))
                .isInstanceOf(TqlException.class);
    }

    @Test
    void pkixAcceptsCertificateSignedByTrustedCa() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(pem("ca.pem"), Map.of("billing-service", billing(BILLING_DN, null, null))));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    @Test
    void pkixRejectsCertificateFromUntrustedCa() throws Exception {
        // The matcher would accept the rogue cert on identity; the PKIX check against the trusted CA
        // must reject it before identity is even consulted.
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(pem("ca.pem"),
                        Map.of("intruder", billing("CN=intruder,O=Evil", null, null))));
        String rogue = pem("rogue-client.pem");
        assertThatThrownBy(() -> authenticator.authenticate(rogue))
                .isInstanceOf(TqlException.class);
    }
}
