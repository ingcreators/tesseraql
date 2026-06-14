package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
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
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for RS256 bearer validation against a JWKS endpoint (roadmap Phase 25): the
 * runtime fetches public keys from a local JWKS server, accepts a token signed by a published key,
 * rejects one signed by an unknown key, and picks up a rotated-in key. The JWKS endpoint is a
 * plain {@link HttpServer} so no external mock library is needed.
 */
@Testcontainers
class RsaJwksIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();

    /** kid -> keypair the JWKS server currently publishes; mutable so a test can rotate. */
    private static final Map<String, KeyPair> PUBLISHED = new ConcurrentHashMap<>();
    private static KeyPair rogueKey;

    static HttpServer jwksServer;
    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PUBLISHED.put("key-1", gen.generateKeyPair());
        rogueKey = gen.generateKeyPair();

        jwksServer = HttpServer.create(new InetSocketAddress("127.0.0.1", freePort()), 0);
        jwksServer.createContext("/jwks", exchange -> {
            byte[] body = jwksDocument().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        jwksServer.start();

        seedDatabase();
        appHome = prepareAppHome(jwksServer.getAddress().getPort());
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (jwksServer != null) {
            jwksServer.stop(0);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void acceptsTokenSignedByPublishedKey() throws Exception {
        HttpResponse<String> response = get("/api/users", token("key-1"));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsTokenSignedByUnknownKey() throws Exception {
        String jwt = rs256Token(claims(), rogueKey.getPrivate(), "rogue-kid");
        HttpResponse<String> response = get("/api/users", jwt);
        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void acceptsRotatedKeyAfterJwksRefresh() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        PUBLISHED.put("key-2", gen.generateKeyPair()); // the IdP rotates in a new key
        HttpResponse<String> response = get("/api/users", token("key-2"));
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static Map<String, Object> claims() {
        return Map.of("sub", "svc-1", "roles", List.of("USER_READ"));
    }

    private static String token(String kid) throws Exception {
        return rs256Token(claims(), PUBLISHED.get(kid).getPrivate(), kid);
    }

    private static String rs256Token(Map<String, Object> claims, PrivateKey signingKey, String kid)
            throws Exception {
        String header = ENC.encodeToString(("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid
                + "\"}").getBytes(StandardCharsets.UTF_8));
        String payload = ENC.encodeToString(MAPPER.writeValueAsBytes(claims));
        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(signingKey);
        rsa.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + ENC.encodeToString(rsa.sign());
    }

    private static String jwksDocument() {
        StringBuilder keys = new StringBuilder();
        PUBLISHED.forEach((kid, pair) -> {
            RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
            if (keys.length() > 0) {
                keys.append(',');
            }
            keys.append("{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"").append(kid)
                    .append("\",\"n\":\"").append(b64(pub.getModulus().toByteArray()))
                    .append("\",\"e\":\"").append(b64(pub.getPublicExponent().toByteArray()))
                    .append("\"}");
        });
        return "{\"keys\":[" + keys + "]}";
    }

    private static String b64(byte[] bytes) {
        int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        byte[] trimmed = new byte[bytes.length - start];
        System.arraycopy(bytes, start, trimmed, 0, trimmed.length);
        return ENC.encodeToString(trimmed);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + bearer).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32), created_at timestamp default now())");
            statement.execute("insert into users (name, status) values ('a','ACTIVE')");
        }
    }

    private static Path prepareAppHome(int jwksPort) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-rs256-it");
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
        // Switch the example's bearer config from HS256 to RS256 backed by the local JWKS server.
        // refreshFloor: 0s lets a rotated-in kid be fetched immediately (no DoS-guard wait).
        Path config = target.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config).replace(
                "      secret: ${JWT_SECRET:dev-only-secret-change-me-in-production}",
                "      algorithm: RS256\n"
                        + "      jwksUri: http://127.0.0.1:" + jwksPort + "/jwks\n"
                        + "      jwks:\n"
                        + "        refreshFloor: 0s"));
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
