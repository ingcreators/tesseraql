package io.tesseraql.saml;

import java.io.ByteArrayInputStream;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Extracts the IdP signing public key from IdP SAML metadata (design ch. 10.14): it reads the
 * {@code IDPSSODescriptor}'s signing {@code KeyDescriptor} certificate, giving the pinned key the
 * validator trusts. Parsing is XXE-safe (DTD and external entities disallowed).
 */
public final class IdpMetadata {

    private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";

    private IdpMetadata() {
    }

    /** The IdP signing public key from its metadata XML. */
    public static PublicKey signingKey(byte[] metadataXml) {
        Document document = parseSecure(metadataXml);
        String certificate = signingCertificate(document);
        if (certificate == null) {
            throw new SamlException("IdP metadata has no signing certificate");
        }
        try {
            byte[] der = Base64.getMimeDecoder().decode(certificate);
            return CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
        } catch (Exception ex) {
            throw new SamlException("IdP metadata certificate is invalid: " + ex.getMessage(), ex);
        }
    }

    /** The signing certificate text: a {@code use="signing"} KeyDescriptor, else one with no use. */
    private static String signingCertificate(Document document) {
        NodeList descriptors = document.getElementsByTagNameNS(MD_NS, "KeyDescriptor");
        String fallback = null;
        for (int i = 0; i < descriptors.getLength(); i++) {
            Element descriptor = (Element) descriptors.item(i);
            String use = descriptor.getAttribute("use");
            String certificate = certificateText(descriptor);
            if (certificate == null) {
                continue;
            }
            if ("signing".equals(use)) {
                return certificate;
            }
            if (use.isEmpty() && fallback == null) {
                fallback = certificate;
            }
        }
        return fallback;
    }

    private static String certificateText(Element keyDescriptor) {
        NodeList certificates = keyDescriptor.getElementsByTagNameNS(DSIG_NS, "X509Certificate");
        if (certificates.getLength() == 0) {
            return null;
        }
        String text = certificates.item(0).getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static Document parseSecure(byte[] xml) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd",
                    false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (Exception ex) {
            throw new SamlException("IdP metadata is not well-formed XML: " + ex.getMessage(), ex);
        }
    }
}
