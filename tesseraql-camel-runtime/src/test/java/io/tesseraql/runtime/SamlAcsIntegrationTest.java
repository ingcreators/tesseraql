package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Integration test for the SAML ACS route (design ch. 10.14): a signed SAML response posted to
 * {@code /_tesseraql/saml/acs} authenticates the user and issues a session cookie, while a tampered
 * response is rejected with 401.
 */
@Testcontainers
class SamlAcsIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROTOCOL_NS = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String ASSERTION_NS = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String AUDIENCE = "https://sp.example.com/saml";
    private static final String RECIPIENT = "https://sp.example.com/_tesseraql/saml/acs";
    private static final Instant NOW = Instant.now();

    static KeyPair keyPair;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void signedResponseIssuesSession() throws Exception {
        String saml = signedAssertion();
        HttpResponse<String> response = postAcs(saml);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Set-Cookie")).isPresent()
                .get().asString().contains("tesseraql_sid=");
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("ok").asBoolean()).isTrue();
        assertThat(body.get("loginId").asText()).isEqualTo("alice");
        assertThat(body.get("subject").asText()).isEqualTo("alice@idp.example.com");
    }

    @Test
    void tamperedResponseIsRejected() throws Exception {
        String saml = signedAssertion().replace("alice@idp.example.com", "mallory@idp.example.com");
        HttpResponse<String> response = postAcs(saml);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> postAcs(String samlResponseXml) throws Exception {
        String body = "SAMLResponse=" + URLEncoder.encode(
                Base64.getEncoder().encodeToString(samlResponseXml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                                "http://localhost:" + runtime.port() + "/_tesseraql/saml/acs"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // --- SAML response builder / signer ----------------------------------------------------------

    private static String signedAssertion() throws Exception {
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
                      <saml:NameID>alice@idp.example.com</saml:NameID>
                      <saml:SubjectConfirmation Method="urn:oasis:names:tc:SAML:2.0:cm:bearer">
                        <saml:SubjectConfirmationData NotOnOrAfter="%s" Recipient="%s"/>
                      </saml:SubjectConfirmation>
                    </saml:Subject>
                    <saml:Conditions NotBefore="%s" NotOnOrAfter="%s">
                      <saml:AudienceRestriction><saml:Audience>%s</saml:Audience></saml:AudienceRestriction>
                    </saml:Conditions>
                    <saml:AuthnStatement AuthnInstant="%s" SessionIndex="session-1"/>
                    <saml:AttributeStatement>
                      <saml:Attribute Name="uid"><saml:AttributeValue>alice</saml:AttributeValue></saml:Attribute>
                      <saml:Attribute Name="role">
                        <saml:AttributeValue>ADMIN</saml:AttributeValue>
                        <saml:AttributeValue>USER</saml:AttributeValue>
                      </saml:Attribute>
                    </saml:AttributeStatement>
                  </saml:Assertion>
                </samlp:Response>
                """.formatted(PROTOCOL_NS, ASSERTION_NS, NOW, NOW, NOW.plusSeconds(300), RECIPIENT,
                NOW.minusSeconds(60), NOW.plusSeconds(300), AUDIENCE, NOW);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        Element assertion = (Element) document.getElementsByTagNameNS(ASSERTION_NS, "Assertion").item(0);
        assertion.setIdAttribute("ID", true);
        signEnveloped(assertion, firstChild(assertion, "Subject"), "#assertion-id", keyPair.getPrivate());
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
        XMLSignature signature = factory.newXMLSignature(signedInfo, null);
        signature.sign(context);
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

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-saml-acs-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Path saml = target.resolve("saml");
        Files.createDirectories(saml);
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(saml.resolve("idp.pem"), pem);

        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  saml:
                    enabled: true
                    sp:
                      audience: %s
                      acsUrl: %s
                    idp:
                      publicKey: saml/idp.pem
                    attributes:
                      loginId: uid
                      roles: role
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                AUDIENCE, RECIPIENT));
        return target;
    }

    private static void copy(Path source, Path target, Path path) {
        try {
            Path destination = target.resolve(source.relativize(path).toString());
            if (Files.isDirectory(path)) {
                Files.createDirectories(destination);
            } else {
                Files.createDirectories(destination.getParent());
                Files.copy(path, destination);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
