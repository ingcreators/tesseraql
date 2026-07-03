package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The Prometheus exposition (roadmap Phase 45, decision point 9): opt-in, bearer + policy
 * gated by default, and fed by the always-on JDK-only aggregating meter — invoking a route
 * shows up as a labelled latency histogram and invocation counter on the next scrape.
 */
@Testcontainers
class MetricsEndpointIntegrationTest {

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
    void scrapeIsPolicyGatedAndShowsRouteHistogramsAfterAnInvocation() throws Exception {
        // Drive one request through a route so the meter has something to expose.
        assertThat(get("/api/ping", null).statusCode()).isEqualTo(200);

        // No bearer -> the scrape is refused (metric labels reveal route ids).
        assertThat(get("/_tesseraql/metrics", null).statusCode()).isEqualTo(401);
        // A bearer without the ops.metrics.view policy role is refused too.
        assertThat(get("/_tesseraql/metrics", token(List.of("NOBODY"))).statusCode())
                .isEqualTo(403);

        HttpResponse<String> scrape = get("/_tesseraql/metrics", token(List.of("OPS")));
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.headers().firstValue("Content-Type").orElse(""))
                .contains("version=0.0.4");
        assertThat(scrape.body())
                .contains("# TYPE tesseraql_route_invocations_total counter")
                .contains("tesseraql_route_invocations_total{method=\"GET\","
                        + "routeId=\"ping\"}")
                .contains("# TYPE tesseraql_route_duration_seconds histogram")
                .contains("routeId=\"ping\"")
                .contains("outcome=\"2xx\"")
                .contains("le=\"+Inf\"")
                .contains("tesseraql_route_duration_seconds_count");
    }

    private static HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(Map.of(
                "sub", "metrics-scraper", "roles", roles)));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path target = Files.createTempDirectory("tesseraql-metrics-it");
        Files.createDirectories(target.resolve("config"));
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0

                tesseraql:
                  app:
                    name: metrics-it
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                  security:
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      rolesClaim: roles
                    policies:
                      ops.metrics.view:
                        anyOf:
                          - role: OPS
                  metrics:
                    enabled: true
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Path pingDir = target.resolve("web/api/ping");
        Files.createDirectories(pingDir);
        Files.writeString(pingDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(pingDir.resolve("ping.sql"), "select 'pong' as answer\n");
        return target;
    }
}
