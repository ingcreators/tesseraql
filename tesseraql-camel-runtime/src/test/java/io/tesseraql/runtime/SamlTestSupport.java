package io.tesseraql.runtime;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/** Test helper: builds and RSA-SHA256-signs SAML 2.0 responses for the SAML SP integration tests. */
final class SamlTestSupport {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";

    private SamlTestSupport() {
    }

    static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** A PEM {@code PUBLIC KEY} encoding of {@code key}, as written to a SAML IdP key file. */
    static String publicKeyPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    /** Builds a SAML response signed at the assertion level with the supplied attributes. */
    static String signedResponse(PrivateKey key, String nameId, String audience, String recipient,
            Instant now, Map<String, List<String>> attributes) throws Exception {
        StringBuilder attrXml = new StringBuilder();
        attributes.forEach((name, values) -> {
            attrXml.append("<saml:Attribute Name=\"").append(name).append("\">");
            values.forEach(v -> attrXml.append("<saml:AttributeValue>").append(v)
                    .append("</saml:AttributeValue>"));
            attrXml.append("</saml:Attribute>");
        });
        String xml = """
                <samlp:Response xmlns:samlp="%s" xmlns:saml="%s" ID="response-id" Version="2.0"
                                IssueInstant="%s">
                  <saml:Issuer>https://idp.example.com</saml:Issuer>
                  <samlp:Status>
                    <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
                  </samlp:Status>
                  <saml:Assertion ID="assertion-id" Version="2.0" IssueInstant="%s">
                    <saml:Issuer>https://idp.example.com</saml:Issuer>
                    <saml:Subject>
                      <saml:NameID>%s</saml:NameID>
                      <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                        <saml:SubjectConfirmationData NotOnOrAfter="%s" Recipient="%s"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                    <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                      <saml:AudienceRestriction><saml:Audience>%s</saml:Audience></saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:AuthnStatement AuthnInstant="%s" SessionIndex="session-1"/>
                    <saml:AttributeStatement>%s</saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(PROTOCOL_NS, ASSERTION_NS, now, now, nameId, now.plusSeconds(300),
                recipient, now.minusSeconds(60), now.plusSeconds(300), audience, now, attrXml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element assertion = (Element) document.getElementsByTagNameNS(ASSERTION_NS, "Assertion").item(0);
        assertion.setIdAttribute("ID", true);
        signEnveloped(assertion, firstChild(assertion, "Subject"), "#assertion-id", key);
        return serialize(document);
    }

    private static void signEnveloped(Element signed, Element nextSibling, String referenceUri,
            PrivateKey key) throws Exception {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = factory.newReference(referenceUri,
                factory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(factory.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null),
                        factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                                (C14NMethodParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(reference));
        DOMSignContext context = new DOMSignContext(key, signed);
        if (nextSibling != null) {
            context.setNextSibling(nextSibling);
        }
        factory.newXMLSignature(signedInfo, null).sign(context);
    }

    private static Element firstChild(Element parent, String localName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && localName.equals(child.getLocalName())) {
                return child;
            }
        }
        return null;
    }

    private static String serialize(Document document) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }
}
