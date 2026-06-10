package io.tesseraql.saml;

import java.time.Instant;
import java.util.Objects;

/**
 * Builds a SAML 2.0 {@code LogoutRequest} for SP-initiated single logout (design ch. 10.14, SAML Core
 * §3.7): it asks the IdP to terminate the session identified by the subject NameID and, when known,
 * the SessionIndex from the original assertion.
 *
 * @param issuer       the SP entity id
 * @param destination  the IdP single-logout endpoint the request is addressed to
 * @param nameId       the subject NameID whose session is being terminated
 * @param sessionIndex the SessionIndex from the authenticating assertion, if known
 */
public record LogoutRequest(String issuer, String destination, String nameId, String sessionIndex) {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

    public LogoutRequest {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(nameId, "nameId");
    }

    /** The request XML with the caller-supplied message id and issue instant. */
    public String toXml(String id, Instant issueInstant) {
        String sessionIndexXml = sessionIndex == null ? ""
                : "<samlp:SessionIndex>" + escapeText(sessionIndex) + "</samlp:SessionIndex>";
        return ("""
                <samlp:LogoutRequest xmlns:samlp="%s" xmlns:saml="%s" ID="%s" Version="2.0"\
                 IssueInstant="%s" Destination="%s">\
                <saml:Issuer>%s</saml:Issuer>\
                <saml:NameID>%s</saml:NameID>%s\
                </samlp:LogoutRequest>""")
                .formatted(PROTOCOL_NS, ASSERTION_NS, escapeAttr(id), issueInstant,
                        escapeAttr(destination), escapeText(issuer), escapeText(nameId), sessionIndexXml);
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
