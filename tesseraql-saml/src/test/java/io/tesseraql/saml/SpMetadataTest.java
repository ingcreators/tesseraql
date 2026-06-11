package io.tesseraql.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

class SpMetadataTest {

    private static final String MD_NS = "urn:oasis:names:tc:SAML:2.0:metadata";

    @Test
    void publishesEntityIdAndAcs() throws Exception {
        SpMetadata metadata = new SpMetadata("https://sp.example.com/saml",
                "https://sp.example.com/_tesseraql/saml/acs", null);
        Document document = parse(metadata.toXml());

        Element root = document.getDocumentElement();
        assertThat(root.getLocalName()).isEqualTo("EntityDescriptor");
        assertThat(root.getAttribute("entityID")).isEqualTo("https://sp.example.com/saml");

        Element acs = (Element) document
                .getElementsByTagNameNS(MD_NS, "AssertionConsumerService").item(0);
        assertThat(acs.getAttribute("Location"))
                .isEqualTo("https://sp.example.com/_tesseraql/saml/acs");
        assertThat(acs.getAttribute("Binding"))
                .isEqualTo("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");

        Element nameIdFormat = (Element) document.getElementsByTagNameNS(MD_NS, "NameIDFormat").item(0);
        assertThat(nameIdFormat.getTextContent())
                .isEqualTo("urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified");
    }

    @Test
    void escapesAttributeValues() throws Exception {
        SpMetadata metadata = new SpMetadata("https://sp/a&b", "https://sp/acs?x=1&y=2", null);
        Document document = parse(metadata.toXml()); // would throw if escaping were wrong
        assertThat(document.getDocumentElement().getAttribute("entityID")).isEqualTo("https://sp/a&b");
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
