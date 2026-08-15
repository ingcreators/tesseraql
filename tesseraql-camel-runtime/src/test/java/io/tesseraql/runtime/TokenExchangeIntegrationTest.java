package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.security.Principal;
import io.tesseraql.security.session.SessionStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * A session buys a short-lived bearer, and the bearer works
 * (docs/session-token-exchange.md).
 *
 * <p>The assertion that carries the feature is the round trip: a token minted here has to be
 * accepted by this same application's bearer path. That is not obvious — the audience became
 * required in the same campaign, so a token minted without one would be signed correctly and
 * refused on arrival by its own issuer.
 */
@Testcontainers
class TokenExchangeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            try (Stream<Path> files = Files.walk(appHome)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aSessionMintsATokenItsOwnBearerPathAccepts() throws Exception {
        HttpResponse<String> minted = exchange(session("alice", List.of("USER_READ")));

        assertThat(minted.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(minted.body());
        assertThat(body.path("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.path("expiresAt").asText()).isNotBlank();

        // The round trip: this application verifies what it just issued.
        HttpResponse<String> api = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/api/users"))
                        .header("Authorization", "Bearer " + body.path("token").asText())
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(api.statusCode()).isEqualTo(200);
    }

    /** The token carries the session's own principal, not a fresh assertion about it. */
    @Test
    void theTokenCarriesTheSubjectAndRolesTheSessionAlreadyHeld() throws Exception {
        HttpResponse<String> minted = exchange(session("bob", List.of("USER_READ", "USER_WRITE")));

        String payload = new String(java.util.Base64.getUrlDecoder().decode(
                MAPPER.readTree(minted.body()).path("token").asText().split("\\.")[1]),
                java.nio.charset.StandardCharsets.UTF_8);
        var claims = MAPPER.readTree(payload);

        assertThat(claims.path("sub").asText()).isEqualTo("bob");
        assertThat(claims.path("roles").toString()).contains("USER_READ").contains("USER_WRITE");
        // Required since the audience work, and minted without it the token would be refused by
        // the application that issued it.
        assertThat(claims.path("aud").asText()).isEqualTo("https://user-admin.example.com");
        assertThat(claims.path("exp").asLong())
                .isGreaterThan(java.time.Instant.now().getEpochSecond());
    }

    /**
     * No session, no token — and the refusal comes from the CSRF check, which is the guard that
     * makes this endpoint safe to expose at all.
     */
    @Test
    void anUnauthenticatedCallerCannotMintAnything() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/token"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    /**
     * A session cookie alone is not enough.
     *
     * <p>This is the case the endpoint exists to be careful about: without the CSRF check, any page
     * that can make the browser POST could convert the visitor's session into a bearer token which
     * outlives the cookie and carries none of its protections.
     */
    @Test
    void aSessionCookieWithoutTheCsrfTokenIsRefused() throws Exception {
        SessionStore sessions = sessions();
        String sid = sessions.create(new Principal("mallory", "mallory", "Mallory", null,
                List.of(), List.of("USER_READ"), List.of(), Map.of()),
                SessionStore.ClientInfo.NONE);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/token"))
                        .header("Cookie", sessions.cookieName() + "=" + sid)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private static SessionStore sessions() {
        return runtime.camelContext().getRegistry().lookupByNameAndType(
                io.tesseraql.camel.TesseraqlProperties.SESSION_STORE_BEAN,
                SessionStore.class);
    }

    private static String session(String subject, List<String> roles) {
        return sessions().create(new Principal(subject, subject, subject, null,
                List.of(), roles, List.of(), Map.of()), SessionStore.ClientInfo.NONE);
    }

    private static HttpResponse<String> exchange(String sid) throws Exception {
        SessionStore sessions = sessions();
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + "/_tesseraql/token"))
                        .header("Cookie", sessions.cookieName() + "=" + sid)
                        .header("X-CSRF-Token", sessions.session(sid).csrfToken())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-token-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // Opt in: the endpoint does not exist until somebody decides it should.
        Path config = target.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config).replace("""
                    jwt:
                """, """
                    token:
                      enabled: true
                      ttl: 15m
                    jwt:
                """));
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
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
