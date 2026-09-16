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
 *
 * <p>The clock-skew cases use the committed fixtures with a skew wide enough to reach them
 * deterministically: {@code expired-client.pem} expired on 2020-01-02 and {@code client.pem} was
 * issued on 2026-06-14, so a twenty-year skew covers both edges from either side for decades,
 * while a one-day skew reaches neither.
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

    private static MtlsClient billing(String subjectDn, MtlsConfig.SanMatcher san, String sha256) {
        return new MtlsClient(subjectDn, san, sha256, "svc:billing", "tenant-a",
                List.of("SERVICE"), List.of("invoices:write"), true);
    }

    private static MtlsConfig.SanMatcher san(MtlsConfig.SanType type, String value) {
        return new MtlsConfig.SanMatcher(type, value);
    }

    private static MtlsConfig config(String trustBundle, Map<String, MtlsClient> clients) {
        return config(trustBundle, null, clients);
    }

    private static MtlsConfig config(String trustBundle, java.time.Duration clockSkew,
            Map<String, MtlsClient> clients) {
        return new MtlsConfig(HEADER, trustBundle, clockSkew, clients);
    }

    /** Wide enough to reach both fixtures' edges from today; see the class comment. */
    private static final java.time.Duration WIDE_SKEW = java.time.Duration.ofDays(365L * 20);

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
        MtlsAuthenticator authenticator = new MtlsAuthenticator(config(null, Map.of(
                "billing-service",
                billing(null, san(MtlsConfig.SanType.URI, BILLING_SAN), null))));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    /**
     * The type confusion this grammar exists to close: the fixture carries its identity as a URI
     * name, and a matcher naming any other kind must not be satisfied by it. Untyped matching made
     * these four authenticate — which is how a rogue DNS name could defeat a SPIFFE URI pin.
     */
    @Test
    void aSubjectAlternativeNameOfAnotherKindDoesNotMatch() throws Exception {
        for (MtlsConfig.SanType other : List.of(MtlsConfig.SanType.DNS, MtlsConfig.SanType.EMAIL,
                MtlsConfig.SanType.IP)) {
            MtlsAuthenticator authenticator = new MtlsAuthenticator(config(null,
                    Map.of("billing-service", billing(null, san(other, BILLING_SAN), null))));
            assertThatThrownBy(() -> authenticator.authenticate(pem("client.pem")))
                    .as("a %s matcher must not be satisfied by a URI name", other)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("not recognized");
        }
    }

    /** A URI name compares exactly — only DNS folds case (RFC 4343), and this fixture has no DNS. */
    @Test
    void uriMatchingComparesExactly() throws Exception {
        MtlsAuthenticator uri = new MtlsAuthenticator(config(null, Map.of("billing-service",
                billing(null, san(MtlsConfig.SanType.URI,
                        BILLING_SAN.toUpperCase(java.util.Locale.ROOT)), null))));
        assertThatThrownBy(() -> uri.authenticate(pem("client.pem")))
                .isInstanceOf(TqlException.class);
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

    /**
     * {@code clockSkew} is leeway: it widens the validity window at both ends. It used to narrow
     * it — both {@code now + skew} and {@code now - skew} had to fall inside the window, so a
     * certificate was refused for {@code skew} after issuance and before expiry, the reverse of
     * what the option documents (docs/audit-low-leads.md slice 4, G40).
     */
    @Test
    void clockSkewAdmitsACertificateExpiredWithinTheSkew() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(config(null, WIDE_SKEW,
                Map.of("expired", billing("CN=expired-service,O=Acme", null, null))));
        assertThat(authenticator.authenticate(pem("expired-client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    @Test
    void clockSkewAdmitsACertificateIssuedWithinTheSkew() throws Exception {
        // Inside its window today; a narrowing skew would have demanded issuance twenty
        // years ago.
        MtlsAuthenticator authenticator = new MtlsAuthenticator(config(null, WIDE_SKEW,
                Map.of("billing-service", billing(BILLING_DN, null, null))));
        assertThat(authenticator.authenticate(pem("client.pem")).subject())
                .isEqualTo("svc:billing");
    }

    @Test
    void clockSkewDoesNotAdmitACertificateExpiredBeyondIt() throws Exception {
        MtlsAuthenticator authenticator = new MtlsAuthenticator(
                config(null, java.time.Duration.ofDays(1),
                        Map.of("expired", billing("CN=expired-service,O=Acme", null, null))));
        String expired = pem("expired-client.pem");
        assertThatThrownBy(() -> authenticator.authenticate(expired))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("not valid");
    }

    @Test
    void pkixValidatesAtTheSameLeewayAsTheWindowCheck() throws Exception {
        // The expired fixture is signed by the test CA: a path validated at a strict now would
        // refuse what the window check just admitted, so the chain is validated at the
        // window's nearer edge instead.
        MtlsAuthenticator authenticator = new MtlsAuthenticator(config(pem("ca.pem"), WIDE_SKEW,
                Map.of("expired", billing("CN=expired-service,O=Acme", null, null))));
        assertThat(authenticator.authenticate(pem("expired-client.pem")).subject())
                .isEqualTo("svc:billing");
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
