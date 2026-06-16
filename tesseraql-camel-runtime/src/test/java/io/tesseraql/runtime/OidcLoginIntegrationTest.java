package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * Integration test for the OIDC relying party (roadmap Phase 25): the runtime drives the
 * authorization-code + PKCE flow against a local mock OpenID Provider (a {@link HttpServer} serving
 * discovery, JWKS, and a token endpoint — no external mock library). It asserts a successful login
 * issues a session, and that a tampered state, a replayed state, and an OP error are all rejected.
 */
@Testcontainers
class OidcLoginIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final String CLIENT_ID = "my-app";

    private static KeyPair opKey;
    private static String issuer;
    /** The nonce the mock OP embeds in the next id_token (set by the test from the login redirect). */
    private static volatile String nextNonce;

    static HttpServer mockOp;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        opKey = gen.generateKeyPair();

        int opPort = freePort();
        issuer = "http://127.0.0.1:" + opPort;
        mockOp = HttpServer.create(new InetSocketAddress("127.0.0.1", opPort), 0);
        mockOp.createContext("/.well-known/openid-configuration",
                exchange -> respond(exchange, 200, discoveryDocument()));
        mockOp.createContext("/jwks", exchange -> respond(exchange, 200, jwksDocument()));
        mockOp.createContext("/token", exchange -> respond(exchange, 200, tokenResponse()));
        mockOp.start();

        int runtimePort = freePort();
        appHome = prepareAppHome(opPort, runtimePort);
        runtime = TesseraqlRuntime.start(appHome, runtimePort);
        seedDatabase();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (mockOp != null) {
            mockOp.stop(0);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void successfulLoginIssuesASession() throws Exception {
        String[] login = login();
        nextNonce = login[1];

        HttpResponse<String> callback = get("/_tesseraql/oidc/callback?code=auth-code&state="
                + login[0]);

        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("location")).hasValue("/done");
        assertThat(callback.headers().firstValue("set-cookie"))
                .hasValueSatisfying(cookie -> assertThat(cookie).contains("tesseraql_sid="));
    }

    @Test
    void rejectsTamperedState() throws Exception {
        login();
        HttpResponse<String> callback = get(
                "/_tesseraql/oidc/callback?code=auth-code&state=not-a-real-state");
        assertThat(callback.statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsReplayedState() throws Exception {
        String[] login = login();
        nextNonce = login[1];
        String callbackUrl = "/_tesseraql/oidc/callback?code=auth-code&state=" + login[0];

        assertThat(get(callbackUrl).statusCode()).isEqualTo(302);
        // The single-use state was consumed; a replayed callback is rejected.
        assertThat(get(callbackUrl).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsProviderError() throws Exception {
        String[] login = login();
        HttpResponse<String> callback = get(
                "/_tesseraql/oidc/callback?error=access_denied&state=" + login[0]);
        assertThat(callback.statusCode()).isEqualTo(401);
    }

    /** Starts the flow and returns {state, nonce} parsed from the authorization redirect. */
    private static String[] login() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/oidc/login");
        assertThat(response.statusCode()).isEqualTo(302);
        String location = response.headers().firstValue("location").orElseThrow();
        return new String[]{queryParam(location, "state"), queryParam(location, "nonce")};
    }

    private static String discoveryDocument() {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"end_session_endpoint\":\"" + issuer + "/logout\"}";
    }

    private static String jwksDocument() {
        RSAPublicKey pub = (RSAPublicKey) opKey.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + b64(pub.getModulus().toByteArray()) + "\",\"e\":\""
                + b64(pub.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private static String tokenResponse() {
        long exp = System.currentTimeMillis() / 1000L + 600;
        Map<String, Object> claims = Map.of("iss", issuer, "aud", CLIENT_ID, "nonce", nextNonce,
                "sub", "u-1", "exp", exp, "preferred_username", "alice",
                "roles", List.of("USER_READ"));
        String idToken = rs256(claims);
        return "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":600,"
                + "\"id_token\":\"" + idToken + "\"}";
    }

    private static String rs256(Map<String, Object> claims) {
        try {
            String header = ENC.encodeToString(
                    "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = ENC.encodeToString(MAPPER.writeValueAsBytes(claims));
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initSign(opKey.getPrivate());
            rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
            return header + "." + payload + "." + ENC.encodeToString(rsa.sign());
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String b64(byte[] bytes) {
        int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        byte[] trimmed = new byte[bytes.length - start];
        System.arraycopy(bytes, start, trimmed, 0, trimmed.length);
        return ENC.encodeToString(trimmed);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status,
            String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String queryParam(String url, String name) {
        String query = URI.create(url).getQuery();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("No '" + name + "' in " + url);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
        }
    }

    private static Path prepareAppHome(int opPort, int runtimePort) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-oidc-it");
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
        // Enable the OIDC relying party against the mock OP; linking off, so the principal is the
        // validated ID token (no identity contracts needed).
        Files.writeString(target.resolve("config/tesseraql.yml"), """

                  oidc:
                    enabled: true
                    discoveryUri: http://127.0.0.1:%d/.well-known/openid-configuration
                    clientId: %s
                    clientSecret: test-secret
                    redirectUri: http://localhost:%d/_tesseraql/oidc/callback
                    scopes: [openid, profile, email]
                    postLoginUrl: /done
                    link:
                      enabled: false
                """.formatted(opPort, CLIENT_ID, runtimePort),
                java.nio.file.StandardOpenOption.APPEND);
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
