package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.tesseraql.identity.DefaultIdentityPack;
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
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for OIDC userLink through the identity link (docs/application-roles.md
 * structural decision 3): a first login JIT-provisions a local user keyed by the token's
 * {@code iss} + {@code sub}, mapped claims land as user attributes, and a login-claim change at
 * the OP re-syncs the same account — roles and all — instead of provisioning a duplicate.
 */
@Testcontainers
class OidcUserLinkIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final String CLIENT_ID = "my-app";

    private static KeyPair opKey;
    private static String issuer;
    /** The claims the mock OP embeds in the next id_token (set by each test before its callback). */
    private static volatile String nextNonce;
    private static volatile String nextLogin;
    private static volatile String nextDepartment;

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

        seedIdentitySchema();
        int runtimePort = freePort();
        appHome = prepareAppHome(opPort, runtimePort);
        runtime = TesseraqlRuntime.start(appHome, runtimePort);
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
    void renamedLoginClaimResyncsTheSameAccountThroughTheIdentityLink() throws Exception {
        // First login provisions eve with her mapped department attribute.
        login("eve", "開発部");
        String userId = scalar("select user_id from tql_users where login_id = 'eve'");
        assertThat(userId).isNotNull().isNotEqualTo("eve");
        assertThat(scalar("select user_id from tql_user_identities where provider = '" + issuer
                + "' and external_subject = 'sub-eve'")).isEqualTo(userId);
        assertThat(scalar("select value from tql_user_attributes where user_id = '" + userId
                + "' and name = 'department'")).isEqualTo("開発部");

        // The deployment assigns a role between logins.
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            statement.execute("insert into tql_roles (role_id, role_code, role_name)"
                    + " values ('r-eve','orders.approver','承認者')");
            statement.execute("insert into tql_user_roles (user_id, role_id)"
                    + " values ('" + userId + "','r-eve')");
        }

        // The OP renames the login claim and moves the department: same sub, same account.
        login("eve.renamed", "経理部");
        assertThat(scalar("select login_id from tql_users where user_id = '" + userId + "'"))
                .isEqualTo("eve.renamed");
        assertThat(scalar("select count(*) from tql_users where login_id like 'eve%'"))
                .isEqualTo("1");
        assertThat(scalar("select value from tql_user_attributes where user_id = '" + userId
                + "' and name = 'department'")).isEqualTo("経理部");
        assertThat(scalar("select count(*) from tql_user_roles where user_id = '" + userId
                + "'")).isEqualTo("1");
    }

    /** Runs the full code flow with the OP asserting {@code login} and {@code department}. */
    private static void login(String login, String department) throws Exception {
        HttpResponse<String> start = get("/_tesseraql/oidc/login");
        assertThat(start.statusCode()).isEqualTo(302);
        String location = start.headers().firstValue("location").orElseThrow();
        nextNonce = queryParam(location, "nonce");
        nextLogin = login;
        nextDepartment = department;
        HttpResponse<String> callback = get("/_tesseraql/oidc/callback?code=auth-code&state="
                + queryParam(location, "state"));
        assertThat(callback.statusCode()).isEqualTo(302);
        assertThat(callback.headers().firstValue("set-cookie"))
                .hasValueSatisfying(cookie -> assertThat(cookie).contains("tesseraql_sid="));
    }

    private static String discoveryDocument() {
        return "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\"}";
    }

    private static String jwksDocument() {
        RSAPublicKey pub = (RSAPublicKey) opKey.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"n\":\""
                + b64(pub.getModulus().toByteArray()) + "\",\"e\":\""
                + b64(pub.getPublicExponent().toByteArray()) + "\"}]}";
    }

    private static String tokenResponse() {
        long exp = System.currentTimeMillis() / 1000L + 600;
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", CLIENT_ID);
        claims.put("nonce", nextNonce);
        claims.put("sub", "sub-eve");
        claims.put("exp", exp);
        claims.put("preferred_username", nextLogin);
        claims.put("department", nextDepartment);
        String idToken = rs256(claims);
        return "{\"access_token\":\"at\",\"token_type\":\"Bearer\",\"expires_in\":600,"
                + "\"id_token\":\"" + idToken + "\"}";
    }

    private static String rs256(Map<String, Object> claims) {
        try {
            String header = ENC.encodeToString(
                    "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            String payload = ENC
                    .encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(claims)));
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

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String scalar(String sql) throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void seedIdentitySchema() throws Exception {
        try (Connection connection = connection();
                Statement statement = connection.createStatement()) {
            for (String ddl : DefaultIdentityPack.schema("postgres").split(";")) {
                if (!ddl.isBlank()) {
                    statement.execute(ddl);
                }
            }
        }
    }

    private static Path prepareAppHome(int opPort, int runtimePort) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-oidc-link-it");
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
        // Link mode on: the principal is the locally-resolved user, keyed by iss + sub, with
        // the department claim captured as a store attribute.
        Files.writeString(target.resolve("config/tesseraql.yml"), """

                  oidc:
                    enabled: true
                    discoveryUri: http://127.0.0.1:%d/.well-known/openid-configuration
                    clientId: %s
                    clientSecret: test-secret
                    redirectUri: http://localhost:%d/_tesseraql/oidc/callback
                    claims:
                      map:
                        department: department
                    link:
                      enabled: true
                      provision: true
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
