package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * The dev-token minting loop (docs/getting-started.md): the token verifies against the
 * app's configured secret, roles land under the configured rolesClaim, JSON-looking claim
 * values embed structurally, and an asymmetric-only app has nothing to sign with.
 */
class TokenCommandTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void mintsAVerifiableTokenWithConfiguredClaimNames(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: token-test
                  security:
                    jwt:
                      secret: unit-test-secret
                      audience: https://app.example.com
                      rolesClaim: groups
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stdout = System.out;
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = new CommandLine(new TokenCommand()).execute("--app", dir.toString(),
                    "--sub", "aoki", "--role", "SUPPLIER", "--claim", "partner=P-100",
                    "--claim", "departments=[\"engineering\",\"sales\"]", "--ttl", "30m");
        } finally {
            System.setOut(stdout);
        }
        assertThat(exit).isZero();

        String token = out.toString(StandardCharsets.UTF_8).trim();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // The signature verifies against the configured secret.
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("unit-test-secret".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[2]).isEqualTo(expected);

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("sub").asText()).isEqualTo("aoki");
        assertThat(payload.get("loginId").asText()).isEqualTo("aoki");
        // Roles land under the CONFIGURED claim name, not a hardcoded one.
        assertThat(payload.get("groups").get(0).asText()).isEqualTo("SUPPLIER");
        assertThat(payload.get("partner").asText()).isEqualTo("P-100");
        assertThat(payload.get("departments").isArray()).isTrue();
        assertThat(payload.get("departments").get(1).asText()).isEqualTo("sales");
        assertThat(payload.get("exp").asLong())
                .isBetween(System.currentTimeMillis() / 1000 + 1500,
                        System.currentTimeMillis() / 1000 + 1900);
    }

    @Test
    void anAsymmetricOnlyAppHasNothingToSignWith(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: token-test
                  security:
                    jwt:
                      jwksUri: https://idp.example.com/jwks
                      audience: https://app.example.com
                """);

        int exit = new CommandLine(new TokenCommand())
                .execute("--app", dir.toString(), "--role", "ADMIN");

        assertThat(exit).isEqualTo(1);
    }

    /**
     * The {@code --url} flow against a stub that behaves like a runtime
     * (docs/stack-architecture.md Decision 20): sign in, take the CSRF token out of the JSON
     * answer, present it with the cookie, print only the token.
     *
     * <p>The stub asserts the wire sequence rather than the outcome, because the outcome is easy
     * to fake and the sequence is what the endpoint requires: a cookie with no CSRF token is
     * exactly the request the guard exists to refuse.
     */
    @Test
    void signsInAndExchangesAgainstARunningApplication() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, "{\"ok\":true,\"loginId\":\"you\",\"csrfToken\":\"c-42\"}");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream stdout = System.out;
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            int exit;
            try {
                exit = new CommandLine(new TokenCommand()).execute("--url",
                        "http://localhost:" + server.getAddress().getPort(),
                        "--login", "you", "--password", "s3cret");
            } finally {
                System.setOut(stdout);
            }

            assertThat(exit).isZero();
            // Only the token, so the command pipes.
            assertThat(out.toString(StandardCharsets.UTF_8).trim()).isEqualTo("minted.jwt.value");
            assertThat(seen).containsExactly(
                    "POST /_tesseraql/login {\"loginId\":\"you\",\"password\":\"s3cret\"}"
                            + " cookie=null csrf=null",
                    "POST /_tesseraql/token {} cookie=tesseraql_sid=s-1 csrf=c-42");
        } finally {
            server.stop(0);
        }
    }

    /**
     * An application older than the returned {@code csrfToken} leaves a command-line caller with a
     * session it cannot use. Saying that is the point: the failure otherwise arrives one step later
     * as a 403 the caller has no way to act on.
     */
    @Test
    void saysSoWhenTheLoginAnswerCarriesNoCsrfToken() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        HttpServer server = stub(seen, "{\"ok\":true,\"loginId\":\"you\"}");
        try {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            PrintStream stderr = System.err;
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int exit;
            try {
                exit = new CommandLine(new TokenCommand()).execute("--url",
                        "http://localhost:" + server.getAddress().getPort(),
                        "--login", "you", "--password", "s3cret");
            } finally {
                System.setErr(stderr);
            }

            assertThat(exit).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("csrfToken");
            // It stopped rather than exchanging without one.
            assertThat(seen).hasSize(1);
        } finally {
            server.stop(0);
        }
    }

    /** The two modes are alternatives; neither and both are the same mistake. */
    @Test
    void refusesNeitherModeAndBothModes(@TempDir Path dir) {
        assertThat(new CommandLine(new TokenCommand()).execute()).isEqualTo(2);
        assertThat(new CommandLine(new TokenCommand())
                .execute("--app", dir.toString(), "--url", "http://localhost:1")).isEqualTo(2);
    }

    /**
     * A claim option against {@code --url} is refused rather than dropped: the server decides what
     * the token carries, so honoring the flag is impossible and ignoring it hands back a token that
     * silently disagrees with the command that asked for it.
     */
    @Test
    void refusesLocalMintingOptionsAgainstARunningApplication() {
        assertThat(new CommandLine(new TokenCommand()).execute("--url", "http://localhost:1",
                "--login", "you", "--password", "x", "--role", "ADMIN")).isEqualTo(2);
    }

    /** A stub runtime: the login answer is the variable, the token answer is fixed. */
    private static HttpServer stub(List<String> seen, String loginAnswer) throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("localhost", 0), 0);
        server.createContext("/_tesseraql/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " " + body
                    + " cookie=" + exchange.getRequestHeaders().getFirst("Cookie")
                    + " csrf=" + exchange.getRequestHeaders().getFirst("X-CSRF-Token"));
            boolean login = exchange.getRequestURI().getPath().endsWith("/login");
            if (login) {
                exchange.getResponseHeaders().add("Set-Cookie",
                        "tesseraql_sid=s-1; Path=/; HttpOnly");
            }
            byte[] answer = (login
                    ? loginAnswer
                    : "{\"token\":\"minted.jwt.value\",\"tokenType\":\"Bearer\","
                            + "\"expiresAt\":\"2026-01-01T00:00:00Z\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            try (java.io.OutputStream out = exchange.getResponseBody()) {
                out.write(answer);
            }
        });
        server.start();
        return server;
    }

    @Test
    void ttlUnitsParse() {
        assertThat(TokenCommand.ttlSeconds("30m")).isEqualTo(1800);
        assertThat(TokenCommand.ttlSeconds("12h")).isEqualTo(43200);
        assertThat(TokenCommand.ttlSeconds("7d")).isEqualTo(604800);
        assertThat(TokenCommand.ttlSeconds("90")).isEqualTo(90);
        assertThatThrownBy(() -> TokenCommand.ttlSeconds("0h"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
