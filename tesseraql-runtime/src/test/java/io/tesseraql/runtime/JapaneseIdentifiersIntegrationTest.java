package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 * The identifier contract over HTTP (docs/unicode-identifiers.md): the 受注管理 gallery app
 * serves Japanese-named routes end-to-end. A browser percent-encodes {@code /受注}, the
 * route matcher decodes it back to the declared path, the {@code {受注番号}} path parameter
 * binds by its own name, a Japanese query-parameter name reaches its bind, and the JSON
 * response carries the column names verbatim as keys.
 */
@Testcontainers
class JapaneseIdentifiersIntegrationTest {

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
    void aPercentEncodedJapanesePathServesTheListWithVerbatimJsonKeys() throws Exception {
        HttpResponse<String> response = get("/%E5%8F%97%E6%B3%A8"); // /受注

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("受注番号").asText()).isEqualTo("J-1001");
        assertThat(data.get(0).path("顧客名").asText()).isEqualTo("山田商事");
    }

    @Test
    void aJapaneseQueryParameterNameReachesItsBind() throws Exception {
        // ?顧客名=佐藤 — both the name and the value arrive percent-encoded.
        HttpResponse<String> response = get("/%E5%8F%97%E6%B3%A8"
                + "?%E9%A1%A7%E5%AE%A2%E5%90%8D=%E4%BD%90%E8%97%A4");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode data = MAPPER.readTree(response.body()).path("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("受注番号").asText()).isEqualTo("J-1002");
    }

    @Test
    void aJapanesePathParameterBindsTheDetailQuery() throws Exception {
        HttpResponse<String> response = get("/%E5%8F%97%E6%B3%A8/J-1002");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode row = MAPPER.readTree(response.body()).path("data").get(0);
        assertThat(row.path("顧客名").asText()).isEqualTo("佐藤物産");
        assertThat(row.path("状態").asText()).isEqualTo("出荷済");
    }

    @Test
    void aDoubleEncodedPathStaysUnmatched() throws Exception {
        // %25E5… decodes to the literal text "%E5…", not to 受注 — one decode only, so an
        // attacker cannot smuggle a second decoding round through the path.
        assertThat(get("/%25E5%258F%2597%25E6%25B3%25A8").statusCode()).isEqualTo(404);
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "juchu-kanri-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-ja-it");
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
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .build();
        return HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "u1", "preferred_username", "tester", "roles",
                        List.of("JUCHU_READ")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
