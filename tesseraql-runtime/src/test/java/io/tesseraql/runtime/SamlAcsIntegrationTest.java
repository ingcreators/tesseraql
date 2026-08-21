package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the SAML ACS route (design ch. 10.14): a signed SAML response posted to
 * {@code /_tesseraql/saml/acs} authenticates the user and issues a session cookie, while a tampered
 * response is rejected with 401.
 */
@Testcontainers
class SamlAcsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AUDIENCE = "https://sp.example.com/saml";
    private static final String RECIPIENT = "https://sp.example.com/_tesseraql/saml/acs";
    private static final Instant NOW = Instant.now();

    static KeyPair keyPair;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        keyPair = SamlTestSupport.generateKeyPair();
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
        HttpResponse<String> response = postAcs(saml());
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
        String saml = saml().replace("alice@idp.example.com", "mallory@idp.example.com");
        HttpResponse<String> rejected = postAcs(saml);

        assertThat(rejected.statusCode()).isEqualTo(401);
        // The framework envelope, not `{"error":"<string>"}`: a federation failure carries a code
        // an operator can search for, like every other error the framework answers with.
        assertThat(rejected.body()).contains("\"code\":\"TQL-SEC-4011\"");
    }

    /**
     * A malformed request is the caller's fault; a broken exchange is not.
     *
     * <p>{@code onException(Exception.class).handled(true)} answered 400 for both, so an operator
     * reading the response learned that their request was invalid when the truth might have been
     * that the server or the IdP had failed. At a federation boundary, whose fault it is happens
     * to be the single most useful thing the answer can carry.
     */
    @Test
    void aMalformedAssertionIsRefusedWithTheFrameworkEnvelope() throws Exception {
        HttpResponse<String> refused = postAcs("not xml at all");

        assertThat(refused.body()).contains("\"code\":\"TQL-SEC-");
        assertThat(refused.body()).doesNotContain("Invalid SAML request");
    }

    @Test
    void metadataEndpointPublishesSpDescriptor() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/saml/metadata"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("EntityDescriptor")
                .contains("entityID=\"" + AUDIENCE + "\"")
                .contains("Location=\"" + RECIPIENT + "\"")
                .contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST");
    }

    private static String saml() throws Exception {
        return SamlTestSupport.signedResponse(keyPair.getPrivate(), "alice@idp.example.com",
                AUDIENCE, RECIPIENT, NOW,
                Map.of("uid", List.of("alice"), "role", List.of("ADMIN", "USER")));
    }

    private static HttpResponse<String> postAcs(String samlResponseXml) throws Exception {
        String body = "SAMLResponse=" + URLEncoder.encode(
                Base64.getEncoder()
                        .encodeToString(samlResponseXml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/saml/acs"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-saml-acs-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Path saml = target.resolve("saml");
        Files.createDirectories(saml);
        Files.writeString(saml.resolve("idp.pem"),
                SamlTestSupport.publicKeyPem(keyPair.getPublic()));

        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: saml-acs
                  # This fixture fails credentials on purpose; the throttle under test
                  # elsewhere is disabled visibly (docs/credential-throttle.md).
                  security:
                    credentialThrottle:
                      enabled: false
                  saml:
                    enabled: true
                    allowIdpInitiated: true
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
