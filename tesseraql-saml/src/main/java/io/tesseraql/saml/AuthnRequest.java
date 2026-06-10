package io.tesseraql.saml;

import java.time.Instant;
import java.util.Objects;

/**
 * Builds a SAML 2.0 {@code AuthnRequest} for SP-initiated SSO (design ch. 10.14, SAML Core §3.4): it
 * asks the IdP to authenticate the user and post the assertion back to this SP's ACS via HTTP-POST.
 *
 * @param issuer      the SP entity id
 * @param acsUrl      the Assertion Consumer Service URL the IdP should post the response to
 * @param destination the IdP SSO endpoint the request is addressed to
 */
public record AuthnRequest(String issuer, String acsUrl, String destination) {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String POST_BINDING = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";

    public AuthnRequest {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(acsUrl, "acsUrl");
        Objects.requireNonNull(destination, "destination");
    }

    /** The request XML with the caller-supplied message id and issue instant. */
    public String toXml(String id, Instant issueInstant) {
        return ("""
                <samlp:AuthnRequest xmlns:samlp="%s" xmlns:saml="%s" ID="%s" Version="2.0"\
                 IssueInstant="%s" Destination="%s" ProtocolBinding="%s"\
                 AssertionConsumerServiceURL="%s">\
                <saml:Issuer>%s</saml:Issuer>\
                </samlp:AuthnRequest>""")
                .formatted(PROTOCOL_NS, ASSERTION_NS, escapeAttr(id), issueInstant,
                        escapeAttr(destination), POST_BINDING, escapeAttr(acsUrl), escapeText(issuer));
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
