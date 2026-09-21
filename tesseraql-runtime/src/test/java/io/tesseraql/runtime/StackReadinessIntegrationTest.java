package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The origin's readiness consults its members (docs/deployment-maturity.md decision 3).
 *
 * <p>Measured before this test existed: with the database stopped, the origin path the Kamal
 * template and the container image probe answered {@code {"status":"UP"}} in a millisecond
 * while the member path answered {@code DOWN} and a route answered 500 after thirty seconds —
 * the gateway read its {@code draining} flag and nothing else. A dedicated container, because
 * the test stops it mid-flight.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StackReadinessIntegrationTest {

    // Managed by hand (not @Container) because the test itself stops it mid-flight.
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static MultiAppGateway gateway;
    static Path stack;

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        stack = Files.createTempDirectory("tesseraql-stack-readiness-it");
        prepareMember(stack.resolve("scaffold-demo"));
        gateway = MultiAppGateway.start(stack, 0);
    }

    @AfterAll
    static void stop() throws IOException {
        if (gateway != null) {
            gateway.close();
        }
        POSTGRES.stop();
        if (stack != null) {
            try (Stream<Path> files = Files.walk(stack)) {
                files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    @Order(1)
    void theOriginReportsUpWhileEveryMemberIsHealthy() throws Exception {
        HttpResponse<String> ready = awaitOrigin("UP");
        assertThat(ready.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(ready.body()).get("status").asText()).isEqualTo("UP");
        assertThat(get("/scaffold-demo/_tesseraql/health/ready").statusCode()).isEqualTo(200);
    }

    @Test
    @Order(2)
    void theOriginReportsDownNamingTheMembersOnceTheDatabaseStopsWhileLivenessStaysUp()
            throws Exception {
        POSTGRES.stop();
        HttpResponse<String> ready = awaitOrigin("DOWN");
        assertThat(ready.statusCode())
                .as("every member is down, so the origin sheds: %s", ready.body()).isEqualTo(503);
        JsonNode body = MAPPER.readTree(ready.body());
        assertThat(body.get("status").asText()).isEqualTo("DOWN");
        assertThat(body.get("down")).as("the body names what is down: %s", ready.body())
                .isNotNull();
        assertThat(body.get("down").toString()).contains("scaffold-demo").contains("portal");
        // The member path stays the per-application truth, and liveness never touches a
        // dependency: the process still answers, so an orchestrator does not restart a pod for
        // a database outage it cannot fix.
        assertThat(get("/scaffold-demo/_tesseraql/health/ready").statusCode()).isEqualTo(503);
        assertThat(get("/_tesseraql/health/live").statusCode()).isEqualTo(200);
    }

    /** Polls the origin's readiness until it reports {@code expected}; a probe's own cadence. */
    private static HttpResponse<String> awaitOrigin(String expected) throws Exception {
        HttpResponse<String> response = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            response = get("/_tesseraql/health/ready");
            if (response.body().contains("\"" + expected + "\"")) {
                return response;
            }
            Thread.sleep(500);
        }
        return response;
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + gateway.port() + path))
                .timeout(Duration.ofSeconds(30))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * A member copied from the scaffold gallery app, over the container, with a short borrow. It
     * keeps the name the gallery declares ({@code scaffold-demo}, config/tesseraql.yml), which is
     * its directory under the stack and its address.
     */
    private static void prepareMember(Path target) throws IOException {
        Path source = Paths.get("..", "examples", "scaffold-demo-app").toAbsolutePath()
                .normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
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
            });
        }
        Files.writeString(target.resolve("config/application.yml"), """
                server:
                  port: 0
                db:
                  main:
                    url: %s
                    username: %s
                    password: %s
                tesseraql:
                  datasources:
                    main:
                      # A short borrow timeout so the probe detects the outage fast; the default
                      # 30s would stall this test, not fail it.
                      connectionTimeoutMillis: 2000
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));
    }
}
