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

    /** A parsed inbound logout request: its message id (for InResponseTo) and subject. */
    public record Parsed(String id, String issuer, String nameId) {
    }

    /** Parses an inbound (IdP-initiated) LogoutRequest's id, issuer and NameID. */
    public static Parsed parse(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document document = factory.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(
                            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            org.w3c.dom.Element root = document.getDocumentElement();
            if (!PROTOCOL_NS.equals(root.getNamespaceURI())
                    || !"LogoutRequest".equals(root.getLocalName())) {
                throw new SamlException("Not a SAML LogoutRequest");
            }
            return new Parsed(root.getAttribute("ID"),
                    text(root, ASSERTION_NS, "Issuer"), text(root, ASSERTION_NS, "NameID"));
        } catch (SamlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SamlException("Malformed LogoutRequest: " + ex.getMessage(), ex);
        }
    }

    private static String text(org.w3c.dom.Element root, String ns, String localName) {
        org.w3c.dom.NodeList nodes = root.getElementsByTagNameNS(ns, localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static String escapeAttr(String value) {
        return escapeText(value).replace("\"", "&quot;");
    }

    private static String escapeText(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
