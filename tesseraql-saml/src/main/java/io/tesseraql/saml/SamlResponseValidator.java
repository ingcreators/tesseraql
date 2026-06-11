package io.tesseraql.saml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.crypto.AlgorithmMethod;
import javax.xml.crypto.KeySelector;
import javax.xml.crypto.KeySelectorException;
import javax.xml.crypto.KeySelectorResult;
import javax.xml.crypto.XMLCryptoContext;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMValidateContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Validates a SAML 2.0 {@code Response} for a service provider and returns its trusted assertion
 * (design ch. 10.14). Validation, in order: secure XML parse (no DTD/external entities), top-level
 * status is Success, the XML signature verifies against the pinned IdP key with JDK secure
 * validation, the consumed assertion lies inside the signed element (XML-signature-wrapping guard),
 * and the assertion's conditions and subject confirmation hold for the supplied clock.
 *
 * <p>Anything that does not hold raises {@link SamlException}; nothing untrusted is ever returned.
 */
public final class SamlResponseValidator {

    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String DSIG_NS = "http://www.w3.org/2000/09/xmldsig#";
    private static final String STATUS_SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";

    private final SamlValidationConfig config;

    public SamlResponseValidator(SamlValidationConfig config) {
        this.config = config;
    }

    /** Validates {@code responseXml} as of {@code now}, returning the trusted assertion. */
    public SamlAssertion validate(String responseXml, Instant now) {
        Document document = parseSecure(responseXml);
        Element response = document.getDocumentElement();
        if (!isElement(response, PROTOCOL_NS, "Response")) {
            throw new SamlException("Root element is not a SAML protocol Response");
        }
        requireSuccessStatus(response);
        registerIds(response);

        Element signature = firstSignature(response);
        Element signedElement = verifySignature(document, signature);
        Element assertion = consumeAssertion(signedElement);

        validateConditions(assertion, now);
        validateSubjectConfirmation(assertion, now);
        SamlAssertion extracted = extract(assertion);
        String inResponseTo = response.getAttribute("InResponseTo");
        return new SamlAssertion(extracted.nameId(), extracted.nameIdFormat(),
                extracted.sessionIndex(), extracted.attributes(), extracted.notOnOrAfter(),
                blankToNull(assertion.getAttribute("ID")), blankToNull(inResponseTo));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // --- signature -------------------------------------------------------------------------------

    private Element verifySignature(Document document, Element signatureElement) {
        XMLSignatureFactory factory = XMLSignatureFactory.getInstance("DOM");
        DOMValidateContext context = new DOMValidateContext(
                new PinnedKeySelector(config.idpSigningKey()), signatureElement);
        // Enable JDK secure validation: restricts transforms/algorithms, bounds reference count,
        // and rejects weak digests/signatures -- a key defense for untrusted XML signatures.
        context.setProperty("org.jcp.xml.dsig.secureValidation", Boolean.TRUE);
        try {
            XMLSignature signature = factory.unmarshalXMLSignature(context);
            if (!signature.validate(context)) {
                throw new SamlException("SAML signature is invalid");
            }
            return resolveSignedElement(document, signature);
        } catch (SamlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SamlException("SAML signature validation failed: " + ex.getMessage(), ex);
        }
    }

    /** The element the signature actually covers, resolved from its single same-document reference. */
    private Element resolveSignedElement(Document document, XMLSignature signature) {
        List<Reference> references = signature.getSignedInfo().getReferences();
        if (references.size() != 1) {
            throw new SamlException("SAML signature must cover exactly one reference");
        }
        String uri = references.get(0).getURI();
        if (uri == null || uri.isEmpty()) {
            return document.getDocumentElement();
        }
        if (!uri.startsWith("#")) {
            throw new SamlException("SAML signature reference must be a same-document reference");
        }
        Element signed = document.getElementById(uri.substring(1));
        if (signed == null) {
            throw new SamlException("SAML signature reference does not resolve to an element");
        }
        return signed;
    }

    /**
     * Selects the assertion to consume, guarding against XML signature wrapping: the assertion must be
     * the signed element itself or the single assertion contained within it. Assertions outside the
     * signed subtree are never trusted.
     */
    private Element consumeAssertion(Element signedElement) {
        if (isElement(signedElement, ASSERTION_NS, "Assertion")) {
            return signedElement;
        }
        List<Element> assertions = childElements(signedElement, ASSERTION_NS, "Assertion");
        if (assertions.size() != 1) {
            throw new SamlException(
                    "Expected exactly one signed assertion, found " + assertions.size());
        }
        return assertions.get(0);
    }

    // --- conditions & subject --------------------------------------------------------------------

    private void validateConditions(Element assertion, Instant now) {
        Element conditions = firstChild(assertion, ASSERTION_NS, "Conditions");
        if (conditions == null) {
            throw new SamlException("Assertion has no Conditions");
        }
        Instant notBefore = instant(conditions.getAttribute("NotBefore"));
        Instant notOnOrAfter = instant(conditions.getAttribute("NotOnOrAfter"));
        if (notBefore != null && now.isBefore(notBefore.minus(config.clockSkew()))) {
            throw new SamlException("Assertion is not yet valid");
        }
        if (notOnOrAfter != null && !now.isBefore(notOnOrAfter.plus(config.clockSkew()))) {
            throw new SamlException("Assertion has expired");
        }
        requireAudience(conditions);
    }

    private void requireAudience(Element conditions) {
        List<String> audiences = new ArrayList<>();
        for (Element restriction : childElements(conditions, ASSERTION_NS, "AudienceRestriction")) {
            for (Element audience : childElements(restriction, ASSERTION_NS, "Audience")) {
                audiences.add(text(audience));
            }
        }
        if (!audiences.contains(config.audience())) {
            throw new SamlException("Assertion audience does not include this SP");
        }
    }

    private void validateSubjectConfirmation(Element assertion, Instant now) {
        Element subject = firstChild(assertion, ASSERTION_NS, "Subject");
        if (subject == null) {
            throw new SamlException("Assertion has no Subject");
        }
        for (Element confirmation : childElements(subject, ASSERTION_NS, "SubjectConfirmation")) {
            Element data = firstChild(confirmation, ASSERTION_NS, "SubjectConfirmationData");
            if (data == null) {
                continue;
            }
            Instant notOnOrAfter = instant(data.getAttribute("NotOnOrAfter"));
            if (notOnOrAfter != null && !now.isBefore(notOnOrAfter.plus(config.clockSkew()))) {
                throw new SamlException("Subject confirmation has expired");
            }
            String recipient = data.getAttribute("Recipient");
            if (config.recipient() != null && !config.recipient().equals(recipient)) {
                throw new SamlException("Subject confirmation recipient mismatch");
            }
        }
    }

    // --- extraction ------------------------------------------------------------------------------

    private SamlAssertion extract(Element assertion) {
        Element subject = firstChild(assertion, ASSERTION_NS, "Subject");
        Element nameIdElement = subject == null
                ? null
                : firstChild(subject, ASSERTION_NS, "NameID");
        if (nameIdElement == null || text(nameIdElement).isEmpty()) {
            throw new SamlException("Assertion has no subject NameID");
        }
        String nameId = text(nameIdElement);
        String nameIdFormat = attributeOrNull(nameIdElement, "Format");

        String sessionIndex = null;
        Element authnStatement = firstChild(assertion, ASSERTION_NS, "AuthnStatement");
        if (authnStatement != null) {
            sessionIndex = attributeOrNull(authnStatement, "SessionIndex");
        }

        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (Element statement : childElements(assertion, ASSERTION_NS, "AttributeStatement")) {
            for (Element attribute : childElements(statement, ASSERTION_NS, "Attribute")) {
                String name = attribute.getAttribute("Name");
                List<String> values = new ArrayList<>();
                for (Element value : childElements(attribute, ASSERTION_NS, "AttributeValue")) {
                    values.add(text(value));
                }
                attributes.computeIfAbsent(name, k -> new ArrayList<>()).addAll(values);
            }
        }

        Element conditions = firstChild(assertion, ASSERTION_NS, "Conditions");
        Instant notOnOrAfter = conditions == null
                ? null
                : instant(conditions.getAttribute("NotOnOrAfter"));
        return new SamlAssertion(nameId, nameIdFormat, sessionIndex, attributes, notOnOrAfter);
    }

    // --- helpers ---------------------------------------------------------------------------------

    private void requireSuccessStatus(Element response) {
        Element status = firstChild(response, PROTOCOL_NS, "Status");
        Element statusCode = status == null ? null : firstChild(status, PROTOCOL_NS, "StatusCode");
        if (statusCode == null || !STATUS_SUCCESS.equals(statusCode.getAttribute("Value"))) {
            throw new SamlException("SAML response status is not Success");
        }
    }

    private static Document parseSecure(String xml) {
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
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new SamlException("SAML response is not well-formed XML: " + ex.getMessage(), ex);
        }
    }

    /** Marks every {@code ID} attribute as an XML id, so signature references and lookups resolve. */
    private static void registerIds(Element root) {
        if (root.hasAttribute("ID")) {
            root.setIdAttribute("ID", true);
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                registerIds(child);
            }
        }
    }

    private static Element firstSignature(Element response) {
        // The signature is on the Response or on an Assertion; either way it is a descendant here.
        NodeList nodes = response.getElementsByTagNameNS(DSIG_NS, "Signature");
        if (nodes.getLength() == 0) {
            throw new SamlException("SAML response is not signed");
        }
        return (Element) nodes.item(0);
    }

    private static boolean isElement(Element element, String namespace, String localName) {
        return element != null && namespace.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    private static Element firstChild(Element parent, String namespace, String localName) {
        List<Element> children = childElements(parent, namespace, localName);
        return children.isEmpty() ? null : children.get(0);
    }

    private static List<Element> childElements(Element parent, String namespace, String localName) {
        List<Element> result = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element child && isElement(child, namespace, localName)) {
                result.add(child);
            }
        }
        return result;
    }

    private static String text(Element element) {
        return element.getTextContent() == null ? "" : element.getTextContent().trim();
    }

    private static String attributeOrNull(Element element, String name) {
        return element.hasAttribute(name) ? element.getAttribute(name) : null;
    }

    private static Instant instant(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new SamlException("Invalid SAML timestamp: " + value);
        }
    }

    /** Trusts only the pinned IdP key, ignoring any {@code KeyInfo} carried in the message. */
    private static final class PinnedKeySelector extends KeySelector {
        private final PublicKey key;

        private PinnedKeySelector(PublicKey key) {
            this.key = key;
        }

        @Override
        public KeySelectorResult select(KeyInfo keyInfo, Purpose purpose, AlgorithmMethod method,
                XMLCryptoContext context) throws KeySelectorException {
            return () -> key;
        }
    }
}
