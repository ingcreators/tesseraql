package io.tesseraql.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class SamlRequestTest {

    private static final Instant NOW = Instant.parse("2026-06-10T00:00:00Z");

    @Test
    void authnRequestCarriesAcsAndDestination() {
        String xml = new AuthnRequest("https://sp/saml", "https://sp/acs", "https://idp/sso")
                .toXml("_req1", NOW);
        assertThat(xml)
                .contains("AssertionConsumerServiceURL=\"https://sp/acs\"")
                .contains("Destination=\"https://idp/sso\"")
                .contains("ProtocolBinding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\"")
                .contains("<saml:Issuer>https://sp/saml</saml:Issuer>")
                .contains("ID=\"_req1\"");
    }

    @Test
    void logoutRequestCarriesNameIdAndSessionIndex() {
        String xml = new LogoutRequest("https://sp/saml", "https://idp/slo", "alice@idp", "sess-1")
                .toXml("_req2", NOW);
        assertThat(xml)
                .contains("Destination=\"https://idp/slo\"")
                .contains("<saml:NameID>alice@idp</saml:NameID>")
                .contains("<samlp:SessionIndex>sess-1</samlp:SessionIndex>");
    }

    @Test
    void logoutRequestOmitsSessionIndexWhenAbsent() {
        String xml = new LogoutRequest("https://sp/saml", "https://idp/slo", "alice@idp", null)
                .toXml("_req3", NOW);
        assertThat(xml).doesNotContain("SessionIndex");
    }

    @Test
    void redirectEncodingRoundTrips() {
        String xml = new AuthnRequest("https://sp/saml", "https://sp/acs", "https://idp/sso")
                .toXml("_req4", NOW);
        String encoded = SamlRedirect.deflateAndEncode(xml);
        assertThat(SamlRedirect.decodeAndInflate(encoded)).isEqualTo(xml);
    }
}
