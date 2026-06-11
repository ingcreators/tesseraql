package io.tesseraql.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SamlRedirectSignatureTest {

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Test
    void signedQueryRoundTripsAndCoversRelayState() throws Exception {
        KeyPair keys = keys();
        String encoded = SamlRedirect.deflateAndEncode("<x/>");
        String query = SamlRedirect.signedQuery("SAMLRequest", encoded, "/return",
                keys.getPrivate());
        Map<String, String> params = params(query);
        assertThat(params).containsKeys("SAMLRequest", "RelayState", "SigAlg", "Signature");
        assertThat(params.get("SigAlg")).isEqualTo(SamlRedirect.RSA_SHA256);

        SamlRedirect.verifySignedQuery("SAMLRequest", encoded, "/return",
                params.get("SigAlg"), params.get("Signature"), keys.getPublic());

        // Tampering with the relay state breaks the signature.
        assertThatThrownBy(() -> SamlRedirect.verifySignedQuery("SAMLRequest", encoded, "/evil",
                params.get("SigAlg"), params.get("Signature"), keys.getPublic()))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("does not verify");
    }

    @Test
    void unsignedAndForeignKeyMessagesAreRejected() throws Exception {
        KeyPair keys = keys();
        KeyPair other = keys();
        String encoded = SamlRedirect.deflateAndEncode("<x/>");
        assertThatThrownBy(() -> SamlRedirect.verifySignedQuery("SAMLRequest", encoded, null,
                null, null, keys.getPublic()))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("not signed");

        String query = SamlRedirect.signedQuery("SAMLRequest", encoded, null, keys.getPrivate());
        Map<String, String> params = params(query);
        assertThatThrownBy(() -> SamlRedirect.verifySignedQuery("SAMLRequest", encoded, null,
                params.get("SigAlg"), params.get("Signature"), other.getPublic()))
                .isInstanceOf(SamlException.class);
    }

    @Test
    void logoutMessagesRoundTrip() {
        String xml = new LogoutRequest("https://idp", "https://sp/slo", "alice", null)
                .toXml("_lr1", java.time.Instant.parse("2026-06-11T00:00:00Z"));
        LogoutRequest.Parsed parsed = LogoutRequest.parse(xml);
        assertThat(parsed.id()).isEqualTo("_lr1");
        assertThat(parsed.issuer()).isEqualTo("https://idp");
        assertThat(parsed.nameId()).isEqualTo("alice");

        String response = new LogoutResponse("https://sp", "https://idp/slo", "_lr1")
                .toXml("_r1", java.time.Instant.parse("2026-06-11T00:00:00Z"));
        assertThat(response).contains("InResponseTo=\"_lr1\"").contains("status:Success");
    }

    private static Map<String, String> params(String query) {
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(pair.substring(0, eq), java.net.URLDecoder.decode(
                    pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
        }
        return params;
    }
}
