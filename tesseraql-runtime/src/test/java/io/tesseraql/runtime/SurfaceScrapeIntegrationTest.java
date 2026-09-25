package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.operations.app.StackSettings;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * The stack surface's scrape (docs/capacity-defaults.md decision 13): the stack file's
 * {@code metrics:} is grafted onto the surface, and the origin's {@code /_tesseraql/metrics}
 * reports the pool sign-in rides as {@code pool="main"} — the framework pool, which S5 lent the
 * surface. Read on the surface's own port: the front door forwards every origin
 * {@code /_tesseraql/*} path there, which the relay's own tests hold.
 */
@Testcontainers
class SurfaceScrapeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();
    private static final String SECRET = "dev-only-secret-change-me-in-production";

    /**
     * A placeholder in the stack file's {@code metrics:} resolves where the surface reads it, as
     * the {@code security:} graft's secrets do.
     */
    @Test
    void theOriginScrapeReportsTheSignInPoolAsMain(@TempDir Path stack) throws Exception {
        writeStack(stack, """
                metrics:
                  enabled: true
                  unauthenticated: ${TESSERAQL_TEST_UNSET_SCRAPE_OPEN:true}
                """);

        MultiAppHost host = MultiAppHost.start(stack);
        try {
            HikariDataSource framework = (HikariDataSource) host.context().frameworkDataSource();
            try (Connection held = framework.getConnection()) {
                assertThat(held.isValid(5)).isTrue();
                HttpResponse<String> scrape = get(host, null);
                assertThat(scrape.statusCode()).isEqualTo(200);
                assertThat(active(scrape.body(), "main"))
                        .as("the framework-pool connection this test holds")
                        .isGreaterThanOrEqualTo(1.0);
            }
        } finally {
            host.close();
        }
    }

    /**
     * The same gate as a member's scrape, configured where the surface is: the stack file's
     * {@code security:} carries the bearer's key and the {@code ops.metrics.view} policy.
     */
    @Test
    void withoutUnauthenticatedTheScrapeTakesABearerHoldingTheStacksPolicy(@TempDir Path stack)
            throws Exception {
        writeStack(stack, """
                metrics:
                  enabled: true
                security:
                  jwt:
                    secret: %s
                    audience: %s
                    rolesClaim: roles
                  policies:
                    ops.metrics.view:
                      anyOf:
                        - role: OPS
                """.formatted(SECRET, TestClaims.INLINE_FIXTURE));

        MultiAppHost host = MultiAppHost.start(stack);
        try {
            assertThat(get(host, null).statusCode()).isEqualTo(401);
            assertThat(get(host, token(List.of("NOBODY"))).statusCode()).isEqualTo(403);
            HttpResponse<String> scrape = get(host, token(List.of("OPS")));
            assertThat(scrape.statusCode()).isEqualTo(200);
            assertThat(scrape.body()).contains("tesseraql_pool_connections_active{pool=\"main\"}");
        } finally {
            host.close();
        }
    }

    @Test
    void withoutMetricsTheOriginHasNoScrape(@TempDir Path stack) throws Exception {
        writeStack(stack, "");

        MultiAppHost host = MultiAppHost.start(stack);
        try {
            assertThat(get(host, null).statusCode()).isEqualTo(404);
        } finally {
            host.close();
        }
    }

    private static HttpResponse<String> get(MultiAppHost host, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                "http://" + HostContext.MEMBER_BIND_ADDRESS + ":" + host.surfacePort()
                        + "/_tesseraql/metrics"));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static double active(String scrape, String pool) {
        Matcher matcher = Pattern.compile("tesseraql_pool_connections_active\\{pool=\""
                + Pattern.quote(pool) + "\"\\} ([0-9.]+)").matcher(scrape);
        assertThat(matcher.find()).as("the scrape reports " + pool + ":\n" + scrape).isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(
                TestClaims.addressed(Map.of("sub", "metrics-scraper", "roles", roles))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    /** One application and a stack file supplying the framework pool, plus {@code extra}. */
    private static void writeStack(Path stack, String extra) throws IOException {
        Path config = stack.resolve("orders").resolve("config");
        Files.createDirectories(config);
        Files.writeString(config.resolve("tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                  datasources:
                    main:
                      jdbcUrl: %s
                      username: %s
                      password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        Files.writeString(stack.resolve(StackSettings.FILE_NAME), """
                framework:
                  datasource:
                    jdbcUrl: %s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()) + extra);
    }
}
