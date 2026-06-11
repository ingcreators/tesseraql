package io.tesseraql.saml;

import java.time.Instant;
import java.util.Objects;

/**
 * Builds a SAML 2.0 {@code LogoutResponse} answering an IdP-initiated logout (design ch. 10.14,
 * SAML Core 3.7.2): success status, correlated to the inbound request via {@code InResponseTo}.
 *
 * @param issuer       the SP entity id
 * @param destination  the IdP single-logout endpoint the response is addressed to
 * @param inResponseTo the id of the LogoutRequest being answered
 */
public record LogoutResponse(String issuer, String destination, String inResponseTo) {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";

    public LogoutResponse {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(inResponseTo, "inResponseTo");
    }

    /** The response XML with the caller-supplied message id and issue instant. */
    public String toXml(String id, Instant issueInstant) {
        return ("""
                <samlp:LogoutResponse xmlns:samlp="%s" xmlns:saml="%s" ID="%s" Version="2.0"\
                 IssueInstant="%s" Destination="%s" InResponseTo="%s">\
                <saml:Issuer>%s</saml:Issuer>\
                <samlp:Status><samlp:StatusCode Value="%s"/></samlp:Status>\
                </samlp:LogoutResponse>""")
                .formatted(PROTOCOL_NS, ASSERTION_NS, escape(id), issueInstant,
                        escape(destination), escape(inResponseTo), escapeText(issuer), SUCCESS);
    }

    private static String escape(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
