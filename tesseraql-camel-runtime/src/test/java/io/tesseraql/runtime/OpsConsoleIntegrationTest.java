package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * Integration test for the bundled operations console app (design ch. 26.11, 32, 47): the
 * yaml/template app shipped in tesseraql-ops-ui mounts automatically and renders the ops.* service
 * providers under a strict content security policy; callers without a bearer principal are denied.
 */
@Testcontainers
class OpsConsoleIntegrationTest {

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
            deleteRecursively(appHome);
        }
    }

    @Test
    void rendersHtmlDashboardForAuthorizedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.headers().firstValue("x-frame-options")).hasValue("DENY");
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("TesseraQL Operations Console");
        assertThat(response.body()).contains("Execution lanes");
    }

    @Test
    void overviewUsesSelfHostedHtmxForPolling() throws Exception {
        HttpResponse<String> page = get("/_tesseraql/ops/console", true);
        assertThat(page.body())
                .contains("/assets/vendor/htmx.org/dist/htmx.min.js")
                .contains("hx-trigger=\"every 15s\"");

        // The vendored libraries serve from classpath WebJars at version-less URLs - no external
        // CDN, and upgrades are a pom version bump with templates unchanged.
        HttpResponse<String> htmx = get("/assets/vendor/htmx.org/dist/htmx.min.js", false);
        assertThat(htmx.statusCode()).isEqualTo(200);
        assertThat(htmx.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/javascript"));
        assertThat(htmx.body()).contains("htmx");

        assertThat(page.body())
                .contains("/assets/vendor/hypermedia-components__core/dist/hc.min.css");
        HttpResponse<String> hc = get(
                "/assets/vendor/hypermedia-components__core/dist/hc.min.css", false);
        assertThat(hc.statusCode()).isEqualTo(200);
        assertThat(hc.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/css"));
        assertThat(hc.body()).contains("hc-card");
    }

    @Test
    void rendersFileTransfersPage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/transfers", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        // No ops.app.* grant on this caller: deny-by-default leaves the table empty.
        assertThat(response.body()).contains("File transfers")
                .contains("No file transfers recorded");
    }

    @Test
    void rendersTraceTreePage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/traces", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        // No ops.app.* grant on this caller: deny-by-default leaves the trace table empty.
        assertThat(response.body()).contains("Traces").contains("No traces retained");
    }

    @Test
    void rendersNotFoundPageForUnknownExecution() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/ops/console/executions/missing", true);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Execution not found.");
    }

    @Test
    void requiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/ops/console", false).statusCode()).isEqualTo(401);
        assertThat(get("/_tesseraql/ops/console/traces", false).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> get(String path, boolean auth) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        if (auth) {
            request.header("Authorization", "Bearer " + token());
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "ops-user", "roles", List.of("ADMIN"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-ops-console-it");
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
