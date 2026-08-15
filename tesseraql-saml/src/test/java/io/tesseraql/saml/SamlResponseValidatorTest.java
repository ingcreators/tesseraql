package io.tesseraql.saml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class SamlResponseValidatorTest {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String AUDIENCE = "https://sp.example.com/saml";
    private static final String RECIPIENT = "https://sp.example.com/saml/acs";
    private static final Instant NOW = Instant.parse("2026-06-10T00:00:00Z");

    private static KeyPair keyPair;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    private SamlResponseValidator validator() {
        return validator(null);
    }

    private SamlResponseValidator validator(Duration clockSkew) {
        return new SamlResponseValidator(
                new SamlValidationConfig(AUDIENCE, keyPair.getPublic(), RECIPIENT, clockSkew));
    }

    @Test
    void validSignedAssertionIsAccepted() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true);
        SamlAssertion assertion = validator().validate(xml, NOW);
        assertThat(assertion.nameId()).isEqualTo("alice@example.com");
        assertThat(assertion.sessionIndex()).isEqualTo("session-1");
        assertThat(assertion.attribute("email")).contains("alice@example.com");
        assertThat(assertion.attributes().get("role")).containsExactly("ADMIN", "USER");
    }

    @Test
    void signatureOnResponseRootIsAccepted() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                false);
        assertThat(validator().validate(xml, NOW).nameId()).isEqualTo("alice@example.com");
    }

    @Test
    void tamperedAssertionIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true)
                .replace("alice@example.com", "mallory@example.com");
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class);
    }

    @Test
    void expiredAssertionIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(3600), NOW.minusSeconds(1800),
                AUDIENCE, RECIPIENT, true);
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("expired");
    }

    /** A configured skew moves the expiry boundary: accepted strictly before NotOnOrAfter+skew. */
    @Test
    void customClockSkewMovesTheExpiryBoundary() throws Exception {
        SamlResponseValidator tight = validator(Duration.ofSeconds(30));
        String insideSkew = signedResponse(NOW.minusSeconds(300), NOW.minusSeconds(29),
                AUDIENCE, RECIPIENT, true);
        assertThat(tight.validate(insideSkew, NOW).nameId()).isEqualTo("alice@example.com");
        String onBoundary = signedResponse(NOW.minusSeconds(300), NOW.minusSeconds(30),
                AUDIENCE, RECIPIENT, true);
        assertThatThrownBy(() -> tight.validate(onBoundary, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("expired");
    }

    /** The same skew bounds NotBefore: accepted from NotBefore-skew onwards. */
    @Test
    void customClockSkewMovesTheNotBeforeBoundary() throws Exception {
        SamlResponseValidator tight = validator(Duration.ofSeconds(30));
        String onBoundary = signedResponse(NOW.plusSeconds(30), NOW.plusSeconds(600),
                AUDIENCE, RECIPIENT, true);
        assertThat(tight.validate(onBoundary, NOW).nameId()).isEqualTo("alice@example.com");
        String beyondSkew = signedResponse(NOW.plusSeconds(31), NOW.plusSeconds(600),
                AUDIENCE, RECIPIENT, true);
        assertThatThrownBy(() -> tight.validate(beyondSkew, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("not yet valid");
    }

    /** An unset skew keeps the historical five minutes — no more, no less. */
    @Test
    void defaultClockSkewRemainsFiveMinutes() throws Exception {
        String insideDefault = signedResponse(NOW.minusSeconds(3600), NOW.minusSeconds(299),
                AUDIENCE, RECIPIENT, true);
        assertThat(validator().validate(insideDefault, NOW).nameId())
                .isEqualTo("alice@example.com");
        String onDefaultBoundary = signedResponse(NOW.minusSeconds(3600), NOW.minusSeconds(300),
                AUDIENCE, RECIPIENT, true);
        assertThatThrownBy(() -> validator().validate(onDefaultBoundary, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300),
                "https://attacker.example.com", RECIPIENT, true);
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void wrongRecipientIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300),
                AUDIENCE, "https://attacker.example.com/acs", true);
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("recipient");
    }

    @Test
    void unsignedResponseIsRejected() throws Exception {
        String xml = serialize(buildResponse(NOW.minusSeconds(60), NOW.plusSeconds(300),
                AUDIENCE, RECIPIENT));
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("not signed");
    }

    @Test
    void doctypeIsRejected() {
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY x \"y\">]>"
                + "<samlp:Response xmlns:samlp=\"" + PROTOCOL_NS + "\"/>";
        assertThatThrownBy(() -> validator().validate(xml, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("well-formed");
    }

    @Test
    void signatureWrappingIsResisted() throws Exception {
        Document document = buildResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE,
                RECIPIENT);
        Element response = document.getDocumentElement();
        Element realAssertion = (Element) document.getElementsByTagNameNS(ASSERTION_NS, "Assertion")
                .item(0);

        // Build an unsigned forged assertion (cloned before signing) and inject it ahead of the real
        // one in the Response, simulating an XML-signature-wrapping attack.
        Element forged = (Element) realAssertion.cloneNode(true);
        forged.setAttribute("ID", "forged-id");
        forged.getElementsByTagNameNS(ASSERTION_NS, "NameID").item(0)
                .setTextContent("mallory@example.com");

        realAssertion.setIdAttribute("ID", true);
        signEnveloped(document, realAssertion, realAssertion, firstChild(realAssertion, "Subject"),
                "#assertion-id", keyPair.getPrivate());
        response.insertBefore(forged, realAssertion);

        // The validator binds to the signed assertion, so the forged one is ignored.
        assertThat(validator().validate(serialize(document), NOW).nameId())
                .isEqualTo("alice@example.com");
    }

    /**
     * A comment inside a signed SAML element is refused outright
     * (docs/audit-hardening.md Decision 10).
     *
     * <p>The comment goes in <em>after</em> signing and leaves the concatenated text unchanged, so
     * the signature still verifies — exclusive canonicalization excludes comment nodes from the
     * digest, which is the whole 2018 attribute-truncation class. What the split changes is what a
     * DOM accessor returns: {@code getTextContent()} concatenates and yields the full name id,
     * {@code getFirstChild().getNodeValue()} yields {@code alice} alone. Measured against this
     * validator before the check landed, this document validated and returned the full name id —
     * correct, but correct by an accessor choice a later refactor could reverse with every existing
     * test still green.
     *
     * <p>So the refusal is structural, and this test guards the structure rather than the accessor.
     */
    @Test
    void aCommentInjectedIntoASignedAssertionIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true);
        String injected = xml.replace("alice@example.com</saml:NameID>",
                "alice<!---->@example.com</saml:NameID>");
        assertThat(injected).isNotEqualTo(xml);

        assertThatThrownBy(() -> validator().validate(injected, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("comment or CDATA");
    }

    /**
     * A CDATA section is the same question wearing a different node type, and it also validated
     * before the check landed: the section carries the identical text, so the digest is unchanged.
     */
    @Test
    void aCdataSectionInsideASamlElementIsRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true);
        String injected = xml.replace("alice@example.com</saml:NameID>",
                "<![CDATA[alice@example.com]]></saml:NameID>");
        assertThat(injected).isNotEqualTo(xml);

        assertThatThrownBy(() -> validator().validate(injected, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("comment or CDATA");
    }

    /**
     * A duplicate of the <em>signed</em> id was already refused, and by the JDK rather than by this
     * validator.
     *
     * <p>Worth stating precisely, because it narrows what the new check is for. With
     * {@code secureValidation} enabled, dereferencing the signature's own {@code #assertion-id}
     * raises {@code URIReferenceException: Multiple Elements with the same ID}. The check moves that
     * refusal earlier and states it in the framework's own terms; it does not open a door that was
     * shut.
     */
    @Test
    void aDuplicateOfTheSignedIdIsRejectedBeforeTheSignatureIsProcessed() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true);
        String injected = xml.replace("<samlp:Status>", "<samlp:Status ID=\"assertion-id\">");
        assertThat(injected).isNotEqualTo(xml);

        assertThatThrownBy(() -> validator().validate(injected, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("reuses the ID 'assertion-id'");
    }

    /**
     * A duplicate on an id the signature does <em>not</em> reference is what the check actually adds.
     *
     * <p>The JDK only dereferences the id the signature names, so it never sees this one, and before
     * the check this document validated. Nothing in the framework reads it today either — which is
     * exactly why it is worth refusing now rather than after something does. The signed element is
     * already resolved twice, once inside signature validation and once by {@code getElementById},
     * and what keeps breaking SAML implementations is two components building different answers from
     * identical bytes.
     *
     * <p>Both duplicates sit in the {@code Status} block, outside the signed assertion, so the
     * signature is untouched. Putting one inside the assertion instead simply breaks the digest —
     * which is a different rejection, and would have made this test pass for the wrong reason.
     */
    @Test
    void aDuplicateIdTheSignatureNeverReferencesIsAlsoRejected() throws Exception {
        String xml = signedResponse(NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, RECIPIENT,
                true);
        String injected = xml
                .replace("<samlp:Status>", "<samlp:Status ID=\"shared\">")
                .replace("<samlp:StatusCode Value=", "<samlp:StatusCode ID=\"shared\" Value=");
        assertThat(injected).isNotEqualTo(xml);

        assertThatThrownBy(() -> validator().validate(injected, NOW))
                .isInstanceOf(SamlException.class)
                .hasMessageContaining("reuses the ID 'shared'");
    }

    // --- SAML builder / signer (test only) -------------------------------------------------------

    private static String signedResponse(Instant notBefore, Instant notOnOrAfter, String audience,
            String recipient, boolean signAssertion) throws Exception {
        Document document = buildResponse(notBefore, notOnOrAfter, audience, recipient);
        if (signAssertion) {
            Element assertion = (Element) document.getElementsByTagNameNS(ASSERTION_NS, "Assertion")
                    .item(0);
            assertion.setIdAttribute("ID", true);
            signEnveloped(document, assertion, assertion, firstChild(assertion, "Subject"),
                    "#assertion-id", keyPair.getPrivate());
        } else {
            Element response = document.getDocumentElement();
            response.setIdAttribute("ID", true);
            signEnveloped(document, response, response, firstChild(response, "Status"),
                    "#response-id", keyPair.getPrivate());
        }
        return serialize(document);
    }

    private static Document buildResponse(Instant notBefore, Instant notOnOrAfter, String audience,
            String recipient) throws Exception {
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
                      <saml:NameID Format="urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress">alice@example.com</saml:NameID>
                      <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                        <saml:SubjectConfirmationData NotOnOrAfter="%s" Recipient="%s"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                    <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                      <saml:AudienceRestriction>
                        <saml:Audience>%s</saml:Audience>
                      </saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:AuthnStatement AuthnInstant="%s" SessionIndex="session-1"/>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="email">
                        <saml:AttributeValue>alice@example.com</saml:AttributeValue>
                      </saml:Attribute>
                      <saml:Attribute Name="role">
                        <saml:AttributeValue>ADMIN</saml:AttributeValue>
                        <saml:AttributeValue>USER</saml:AttributeValue>
                      </saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """
                .formatted(PROTOCOL_NS, ASSERTION_NS, NOW, NOW, notOnOrAfter, recipient,
                        notBefore, notOnOrAfter, audience, NOW);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static void signEnveloped(Document document, Element signed, Element insertInto,
            Element nextSibling, String referenceUri, PrivateKey key) throws Exception {
        signed.setIdAttribute("ID", true);
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        Reference reference = factory.newReference(referenceUri,
                factory.newDigestMethod(DigestMethod.SHA256, null),
                List.of(factory.newTransform(Transform.ENVELOPED,
                        (javax.xml.crypto.dsig.spec.TransformParameterSpec) null),
                        factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                                (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null)),
                null, null);
        SignedInfo signedInfo = factory.newSignedInfo(
                factory.newCanonicalizationMethod(CanonicalizationMethod.EXCLUSIVE,
                        (javax.xml.crypto.dsig.spec.C14NMethodParameterSpec) null),
                factory.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                List.of(reference));
        DOMSignContext context = new DOMSignContext(key, insertInto);
        if (nextSibling != null) {
            context.setNextSibling(nextSibling);
        }
        XMLSignature signature = factory.newXMLSignature(signedInfo, null);
        signature.sign(context);
    }

    private static Element firstChild(Element parent, String localName) {
        org.w3c.dom.NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && localName.equals(child.getLocalName())) {
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
