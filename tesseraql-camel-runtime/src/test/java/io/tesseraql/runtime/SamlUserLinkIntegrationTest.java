package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.identity.DefaultIdentityPack;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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
 * Integration test for SAML userLink (design ch. 10.14): a federated login resolves the local
 * identity-store user (so authorization uses local roles), and an unknown federated subject is
 * just-in-time provisioned into the managed realm.
 */
@Testcontainers
class SamlUserLinkIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
        seedIdentitySchema();
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
    void existingFederatedUserResolvesLocalIdentity() throws Exception {
        HttpResponse<String> response = postAcs("alice", Map.of());
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(response.body());
        assertThat(body.get("loginId").asText()).isEqualTo("alice");
        // subject is the local user_id (not the NameID), proving the local account was resolved.
        assertThat(body.get("subject").asText()).isEqualTo("user-alice");
    }

    @Test
    void unknownFederatedUserIsProvisioned() throws Exception {
        HttpResponse<String> response = postAcs("bob@new.example.com",
                Map.of("email", List.of("bob@corp.example.com")));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("subject").asText())
                .isNotBlank().isNotEqualTo("bob@new.example.com");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select display_name, email, status from "
                        + "tql_users where login_id = 'bob@new.example.com'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("display_name")).isEqualTo("bob@new.example.com");
            assertThat(rs.getString("email")).isEqualTo("bob@corp.example.com");
            assertThat(rs.getString("status")).isEqualTo("ACTIVE");
        }
    }

    private static HttpResponse<String> postAcs(String nameId, Map<String, List<String>> attributes)
            throws Exception {
        String xml = SamlTestSupport.signedResponse(keyPair.getPrivate(), nameId, AUDIENCE,
                RECIPIENT,
                NOW, attributes);
        String body = "SAMLResponse=" + URLEncoder.encode(
                Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8);
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/saml/acs"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedIdentitySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
            statement.execute("insert into tql_users (user_id, login_id, display_name, status) "
                    + "values ('user-alice', 'alice', 'Alice', 'ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-saml-link-it");
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
                  saml:
                    enabled: true
                    allowIdpInitiated: true
                    sp:
                      audience: %s
                      acsUrl: %s
                    idp:
                      publicKey: saml/idp.pem
                    attributes:
                      email: email
                    link:
                      enabled: true
                      provision: true
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
