package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.saml.SamlRedirect;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for SP-initiated SSO and single logout (design ch. 10.14): the login route
 * redirects to the IdP with an AuthnRequest, and logout invalidates the session and redirects to the
 * IdP with a LogoutRequest carrying the federated NameID.
 */
@Testcontainers
class SamlSsoLogoutIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String AUDIENCE = "https://sp.example.com/saml";
    private static final String RECIPIENT = "https://sp.example.com/_tesseraql/saml/acs";
    private static final String SSO_URL = "https://idp.example.com/sso";
    private static final String SLO_URL = "https://idp.example.com/slo";
    private static final Instant NOW = Instant.now();

    static KeyPair keyPair;
    static TesseraqlRuntime runtime;
    static Path appHome;
    static HttpClient client;

    @BeforeAll
    static void start() throws Exception {
        keyPair = SamlTestSupport.generateKeyPair();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
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
    void loginRedirectsToIdpWithAuthnRequest() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url("/_tesseraql/saml/login") + "?RelayState=next"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("Location").orElseThrow();
        assertThat(location).startsWith(SSO_URL).contains("SAMLRequest=").contains("RelayState=next");

        String xml = SamlRedirect.decodeAndInflate(samlRequestParam(location));
        assertThat(xml).contains("AuthnRequest")
                .contains("AssertionConsumerServiceURL=\"" + RECIPIENT + "\"");
    }

    @Test
    void logoutInvalidatesSessionAndRedirectsToIdp() throws Exception {
        // First authenticate to obtain a session cookie.
        String acsForm = "SAMLResponse=" + URLEncoder.encode(Base64.getEncoder().encodeToString(
                SamlTestSupport.signedResponse(keyPair.getPrivate(), "dave@idp.example.com",
                        AUDIENCE, RECIPIENT, NOW, Map.of("uid", List.of("dave")))
                        .getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        HttpResponse<String> acs = client.send(
                HttpRequest.newBuilder(URI.create(url("/_tesseraql/saml/acs")))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(acsForm)).build(),
                HttpResponse.BodyHandlers.ofString());
        String cookie = acs.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];

        HttpResponse<String> logout = client.send(
                HttpRequest.newBuilder(URI.create(url("/_tesseraql/saml/logout")))
                        .header("Cookie", cookie).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(logout.statusCode()).isEqualTo(302);
        assertThat(logout.headers().firstValue("Location").orElseThrow()).startsWith(SLO_URL);
        assertThat(logout.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");

        String xml = SamlRedirect.decodeAndInflate(
                samlRequestParam(logout.headers().firstValue("Location").orElseThrow()));
        assertThat(xml).contains("LogoutRequest").contains("<saml:NameID>dave@idp.example.com</saml:NameID>");
    }

    private static String samlRequestParam(String location) {
        String query = URI.create(location).getRawQuery();
        for (String pair : query.split("&")) {
            if (pair.startsWith("SAMLRequest=")) {
                return URLDecoder.decode(pair.substring("SAMLRequest=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("No SAMLRequest in " + location);
    }

    private static String url(String path) {
        return "http://localhost:" + runtime.port() + path;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-saml-sso-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Path saml = target.resolve("saml");
        Files.createDirectories(saml);
        Files.writeString(saml.resolve("idp.pem"), SamlTestSupport.publicKeyPem(keyPair.getPublic()));

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
                      ssoUrl: %s
                      sloUrl: %s
                    attributes:
                      loginId: uid
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                AUDIENCE, RECIPIENT, SSO_URL, SLO_URL));
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
