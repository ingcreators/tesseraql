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

    /** A fixed self-signed test certificate (X.509 DER, base64) and its private key (PKCS8 DER). */
    static final String FIXED_CERT = "MIIDGzCCAgOgAwIBAgIUaqx2kaHliMER8us/H8pOYXTD5HcwDQYJKoZIhvcNAQELBQAwHTEbMBkGA1UEAww"
            + "SdGVzc2VyYXFsLXRlc3QtaWRwMB4XDTI2MDYxMDAyMjg1M1oXDTQ2MDYwNTAyMjg1M1owHTEbMBkGA1UEAww"
            + "SdGVzc2VyYXFsLXRlc3QtaWRwMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAq7cPz5X3OIGRxyo"
            + "haCSkB7c9u6/b5/VFi3RsY4XLCO0ZODUMmjB1CFXmfyZgFWnhJxUYNSLDDQanCFPCWcGybbwRK7bzQ4V2JRE"
            + "dypCaq5V2ZZ2sLmaPffHgjjeM7La0HyWuzGYqrzejNnu7ETowrD3zobvm7P872gCJPZchJETeuFZ9qWMDtGc"
            + "orEgI3fVDgZNjYZ8ddQyT62uBtjHeaysucAChdyStaQeDMatIpNGdA5eVSL79WJ1JoNvhhdEnECkK0jqcxvJ"
            + "Flhe0OZuAdBjVQGMHDzOQPvNr1MlgxAFKWBH2OYhyfChruSfJWcx+6QoFRf7SO/OmgwvRKk1c5QIDAQABo1M"
            + "wUTAdBgNVHQ4EFgQUQ4T30jhga6kxX+exigPjvHFVIUswHwYDVR0jBBgwFoAUQ4T30jhga6kxX+exigPjvHF"
            + "VIUswDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEACtfYnTSijsE0wy/gTPMN0vfRPkQsgLv"
            + "mBHYMAD64YUhlpbwWFTdUJBNr8XIpk04gcD/wSMvuSpDHeSD6le6N7GMi/hmyvATrWyov56Jp9KztzWfRaU1"
            + "RLolkoIgRzPmHWoiDMsLvvM1nR7P7OaIpxTNLkTc8wJ4uDa8YE3Jdj0RLzyGZtkK7ewZS/wQnhDu2F6tm/gl"
            + "BRTNxzz0vuNAxjOP17K+9iq0ne0xaSS7HZXlAbVhFFVob4d5s5G5c1uW53JYnkAMUBSPcWjJLOX06MIKMOSC"
            + "iobhRn3u7jS5mlxRpL/+81Brek03uXnS/QcuqRUcfOnbtJguYSAw6TEluXg==";

    private static final String FIXED_KEY = "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCrtw/Plfc4gZHHKiFoJKQHtz27r9vn9UWL"
            + "dGxjhcsI7Rk4NQyaMHUIVeZ/JmAVaeEnFRg1IsMNBqcIU8JZwbJtvBErtvNDhXYlER3KkJqrlXZlnawuZo9"
            + "98eCON4zstrQfJa7MZiqvN6M2e7sROjCsPfOhu+bs/zvaAIk9lyEkRN64Vn2pYwO0ZyisSAjd9UOBk2Nhnx"
            + "11DJPra4G2Md5rKy5wAKF3JK1pB4Mxq0ik0Z0Dl5VIvv1YnUmg2+GF0ScQKQrSOpzG8kWWF7Q5m4B0GNVAY"
            + "wcPM5A+82vUyWDEAUpYEfY5iHJ8KGu5J8lZzH7pCgVF/tI786aDC9EqTVzlAgMBAAECggEAC1HQxsIvzyeJ"
            + "dNvj4mHh90k+1k/QOxEJ4djNqVzhRsgcfL5Qg5yUpnVFMdYViOvXJhSnqR8OxR3OZofg/Lo+WSoH5r4TeLz"
            + "ETkq70EXqkEuUx7uWZWOo62hFwZJLZbJgmtoI1RwaDb1o8rdC3G6d1UjrNdr1bOOhbixRX8BF9MZC1D10l0"
            + "BRBqPHYhThkeOwtzT0vxRk/xEG08dAbNcIeitsvTGrBsuo4a85yooMkSU4J8FdwIimbzHaofuPspFcMnrgG"
            + "/v2jfYP7UnUKnU3IPFPsmJ0p9Uy2IdBgprmw9QOXMByqR40FiXg6ruuXw5/5fcrojT0IcpIR+t0SSwgkQKB"
            + "gQDURsdNpmkRpW1nAlTSKDNrTfHC/R+kB2Nce6rMBvqz1VpwiwxHoCCQ+/wigqIjkIgqWCUnxyC6dsVNwce"
            + "pTLmr3eDdVlLWKBWYO7tTg2sYb7fLLqMt1hjuVMi0LViQNjIRU/AA/za7QOYZfIFULtV7jtxRuF1uw0X3Oj"
            + "DWSPQgVQKBgQDPFYDbj1CAe4OkucVAeJ6Q96tJupLRHU8JFJU/kTwJQbFXcuR8p6URqlphx3GbSHltZb78Y"
            + "MgNpmhmiqSKlLo5+uNknXRnB9OpftFSZjNkw3P6Zj07QnIJ/7repxNYpqX0XWXiloO3LlfmvX8IFB1W57Ey"
            + "8hu7VVZhwE0b/yyaUQKBgFHw6GpO/Gv1YZ/LxJZDMmYPdm2AbEBTIcXHbwzG/OuCRiD/a8QSSb/tpUxlBNW"
            + "ZqxY9ZEpQkY+o3UzAqqPtnBZ91ZlbAyrr2jojhJIePq72IQprfE3rQUButfLnNjKk2PrbXd/kpGnwCWJ5Ly"
            + "sh0QKbCOz6sAZblpxyd/ufuazpAoGAN/nVCgRUO1anv/gjNIkmO4Nm/pf7JaFpgsfYjAVDGDF0sXGyB2v9d"
            + "6f3pGSX9eSCRirxlCDJEr9/ivBBB+Cp8hA6NTFGjK8V7MQF6uMLU1pt2CqYtJMCZmeE6Lh6x0TMqSAx8SzE"
            + "T9isFAf29YUSZTJduKqvClVH80Za0Y2JTfECgYASsHA/OwJ0EjGFTpaMsavyCoR1+2yvYDjPWQTeqD2hR3S"
            + "7HGQ3AwjMbMw7oZIHWhbyMyo/AG41sbTGg14vK6zzA9uSinqyNPbVofArcSzw1g74q+pmL01xVWs6f+BsMJ"
            + "KomSrJqGZun+rpVWzcY0PNQc2G45gwFeiBiuWNc84A0Q==";

    private SamlTestSupport() {
    }

    /** The private key matching {@link #FIXED_CERT}, for signing responses in idp-metadata tests. */
    static PrivateKey fixedPrivateKey() throws Exception {
        byte[] der = java.util.Base64.getDecoder().decode(FIXED_KEY);
        return java.security.KeyFactory.getInstance("RSA")
                .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
    }

    /** IdP metadata advertising {@link #FIXED_CERT} as the signing certificate. */
    /** The fixed test key as a PKCS#8 PEM, for configs that read a key file. */
    static String fixedPrivateKeyPem() throws Exception {
        return "-----BEGIN PRIVATE KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes())
                        .encodeToString(fixedPrivateKey().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    static String idpMetadataXml(String entityId) {
        return """
                <md:EntityDescriptor xmlns:md="urn:oasis:names:tc:SAML:2.0:metadata" entityID="%s">
                  <md:IDPSSODescriptor protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                    <md:KeyDescriptor use="signing">
                      <ds:KeyInfo xmlns:ds="http://www.w3.org/2000/09/xmldsig#">
                        <ds:X509Data><ds:X509Certificate>%s</ds:X509Certificate></ds:X509Data>
                      </ds:KeyInfo>
                    </md:KeyDescriptor>
                  </md:IDPSSODescriptor>
                </md:EntityDescriptor>
                """
                .formatted(entityId, FIXED_CERT);
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
        return signedResponse(key, nameId, audience, recipient, now, attributes,
                "_a" + java.util.UUID.randomUUID(), null);
    }

    /** As above with an explicit assertion id and optional InResponseTo (replay/SP-initiated tests). */
    static String signedResponse(PrivateKey key, String nameId, String audience, String recipient,
            Instant now, Map<String, List<String>> attributes, String assertionId,
            String inResponseTo) throws Exception {
        StringBuilder attrXml = new StringBuilder();
        attributes.forEach((name, values) -> {
            attrXml.append("<saml:Attribute Name=\"").append(name).append("\">");
            values.forEach(v -> attrXml.append("<saml:AttributeValue>").append(v)
                    .append("</saml:AttributeValue>"));
            attrXml.append("</saml:Attribute>");
        });
        String xml = """
                <samlp:Response xmlns:samlp="%s" xmlns:saml="%s" ID="_r%s" Version="2.0"
                                IssueInstant="%s"%s>
                  <saml:Issuer>https://idp.example.com</saml:Issuer>
                  <samlp:Status>
                    <samlp:StatusCode Value="urn:oasis:names:tc:SAML:2.0:status:Success"/>
                  </samlp:Status>
                  <saml:Assertion ID="%s" Version="2.0" IssueInstant="%s">
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
                """
                .formatted(PROTOCOL_NS, ASSERTION_NS, java.util.UUID.randomUUID(), now,
                        inResponseTo == null ? "" : " InResponseTo=\"" + inResponseTo + "\"",
                        assertionId, now, nameId, now.plusSeconds(300),
                        recipient, now.minusSeconds(60), now.plusSeconds(300), audience, now,
                        attrXml);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element assertion = (Element) document.getElementsByTagNameNS(ASSERTION_NS, "Assertion")
                .item(0);
        assertion.setIdAttribute("ID", true);
        signEnveloped(assertion, firstChild(assertion, "Subject"),
                "#" + assertion.getAttribute("ID"), key);
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
