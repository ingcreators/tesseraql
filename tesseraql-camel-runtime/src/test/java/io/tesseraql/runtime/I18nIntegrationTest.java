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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 22 acceptance (roadmap "internationalization"): the user-admin example serves a
 * Japanese/English UI from one set of templates. The request locale resolves from the
 * {@code ?lang=} preference, then {@code Accept-Language}, then the app default; pages render
 * {@code #{...}} catalog texts with a matching {@code lang} attribute; a validation violation
 * answers with a localized message (and its stable {@code messageKey}) as JSON and as an inline
 * htmx fragment; and the client catalog module merges the app's keys over the kit's Japanese
 * strings before the behaviors install.
 */
@Testcontainers
class I18nIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
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
    void theDefaultLocaleRendersEnglishChrome() throws Exception {
        HttpResponse<String> response = get("/users", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("lang=\"en\"")
                .contains("<h2>Users</h2>")
                .contains("/assets/_tesseraql/messages.js?locale=en");
    }

    @Test
    void acceptLanguageNegotiatesJapanese() throws Exception {
        HttpResponse<String> response = get("/users", Map.of("Accept-Language", "ja, en;q=0.5"));

        assertThat(response.body()).contains("lang=\"ja\"")
                .contains("<h2>ユーザー一覧</h2>")
                .contains("名前で検索...")
                .contains("/assets/_tesseraql/messages.js?locale=ja");
    }

    @Test
    void theLangQueryPreferenceBeatsAcceptLanguage() throws Exception {
        HttpResponse<String> response = get("/users?lang=ja", Map.of("Accept-Language", "en"));

        assertThat(response.body()).contains("lang=\"ja\"")
                .contains("<h2>ユーザー一覧</h2>");
    }

    @Test
    void aValidationViolationAnswersLocalizedJson() throws Exception {
        HttpResponse<String> response = post("/api/users/provision",
                "{\"userName\": \"nobody\"}", Map.of("Accept-Language", "ja"));

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode error = MAPPER.readTree(response.body()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("TQL-FIELD-4220");
        assertThat(error.path("message").asText()).isEqualTo("入力内容を確認してください");
        JsonNode field = error.path("fields").get(0);
        assertThat(field.path("messageKey").asText())
                .isEqualTo("users.provision.unknown-user");
        assertThat(field.path("message").asText()).isEqualTo("指定されたユーザーは存在しません。");
    }

    @Test
    void anHtmxViolationFragmentCarriesLocalizedTextAndTheKey() throws Exception {
        HttpResponse<String> response = post("/api/users/provision",
                "{\"userName\": \"nobody\"}",
                Map.of("Accept-Language", "ja", "HX-Request", "true"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/html");
        assertThat(response.body()).contains("data-hc-field-errors")
                .contains("入力内容を確認してください")
                .contains("data-message-key=\"users.provision.unknown-user\"")
                .contains("指定されたユーザーは存在しません。");
    }

    @Test
    void theClientCatalogModuleMergesAppKeysOverTheKitsJapanese() throws Exception {
        HttpResponse<String> response = get("/assets/_tesseraql/messages.js?locale=ja", Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("text/javascript");
        assertThat(response.body())
                .contains("import { setMessages } from "
                        + "\"/assets/vendor/hypermedia-components__core/dist/hc.behaviors.min.js\"")
                .contains("キャンセル")
                .contains("users.provision.unknown-user");
    }

    private static HttpResponse<String> get(String path, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String json,
            Map<String, String> extraHeaders) throws Exception {
        HttpRequest.Builder request = HttpRequest
                .newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        extraHeaders.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(
                Map.of("sub", "u1", "preferred_username", "admin", "roles",
                        List.of("USER_WRITE"))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("create table users (id serial primary key, name varchar(200), "
                    + "status varchar(32) not null, created_at timestamp default now())");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-i18n-it");
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
