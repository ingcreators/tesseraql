package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test for the Studio backend (design ch. 16): the explorer and source endpoints require
 * a bearer principal, and draft saves are accepted when Studio is not read-only.
 */
@Testcontainers
class StudioIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

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
    void explorerListsRoutesForAuthenticatedCaller() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/explorer", true);
        assertThat(response.statusCode()).isEqualTo(200);

        JsonNode explorer = MAPPER.readTree(response.body());
        assertThat(explorer.get("readOnly").asBoolean()).isFalse();
        assertThat(explorer.get("routes")).anySatisfy(
                route -> assertThat(route.get("id").asText()).isEqualTo("users.search"));
    }

    @Test
    void sourceReturnsFileContents() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/source?path=" + enc("web/api/users/search.sql"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("content").asText()).contains("select");
    }

    @Test
    void draftSaveSucceedsWhenWritable() throws Exception {
        HttpResponse<String> response = post(
                "/_tesseraql/studio/drafts?path=" + enc("web/api/users/get.yml"), "edited", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("saved").asText())
                .isEqualTo("web/api/users/get.yml");
    }

    @Test
    void previewApplyAndReloadFlow() throws Exception {
        String path = "web/api/extra/get.yml";
        String newRoute = """
                version: tesseraql/v1
                id: extra.list
                kind: route
                recipe: query-json
                sql:
                  file: extra.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """;

        HttpResponse<String> preview = post(
                "/_tesseraql/studio/preview?path=" + enc(path), newRoute, true);
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(preview.body()).get("valid").asBoolean()).isTrue();

        assertThat(post("/_tesseraql/studio/drafts?path=" + enc(path), newRoute, true)
                .statusCode()).isEqualTo(200);

        HttpResponse<String> apply = post("/_tesseraql/studio/apply?path=" + enc(path), "", true);
        assertThat(apply.statusCode()).isEqualTo(200);

        HttpResponse<String> reload = post("/_tesseraql/studio/reload", "", true);
        assertThat(reload.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(reload.body()).get("routes"))
                .anySatisfy(route -> assertThat(route.get("id").asText()).isEqualTo("extra.list"));
    }

    @Test
    void explorerRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/explorer", false).statusCode()).isEqualTo(401);
    }

    @Test
    void previewValidatesPageTemplateWithSharedFragments() throws Exception {
        // The real page composes the framework tql/shell fragment and the shared app nav; the
        // preview engine resolves both, so the file validates as authored.
        String content = Files.readString(appHome.resolve("web/users/index.html"));
        HttpResponse<String> preview = post(
                "/_tesseraql/studio/preview?path=" + enc("web/users/index.html"), content, true);

        assertThat(preview.statusCode()).isEqualTo(200);
        var body = MAPPER.readTree(preview.body());
        assertThat(body.get("kind").asText()).isEqualTo("template");
        assertThat(body.get("valid").asBoolean()).isTrue();

        // Malformed markup is rejected through the same endpoint.
        HttpResponse<String> broken = post(
                "/_tesseraql/studio/preview?path=" + enc("web/users/index.html"),
                "<div th:text=\"${x}>oops</div>", true);
        assertThat(MAPPER.readTree(broken.body()).get("valid").asBoolean()).isFalse();
    }

    @Test
    void uiExplorerRendersHtmlPage() throws Exception {
        HttpResponse<String> response = get("/_tesseraql/studio/ui", true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.headers().firstValue("content-security-policy"))
                .hasValueSatisfying(value -> assertThat(value).contains("default-src 'self'"));
        assertThat(response.body()).startsWith("<!DOCTYPE html>");
        assertThat(response.body()).contains("TesseraQL Studio").contains("users.search");
    }

    @Test
    void uiSourceRendersHtmlPage() throws Exception {
        HttpResponse<String> response = get(
                "/_tesseraql/studio/ui/source?path=" + enc("web/api/users/search.sql"), true);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.body()).contains("web/api/users/search.sql").contains("select");
    }

    @Test
    void uiExplorerRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui", false).statusCode()).isEqualTo(401);
    }

    @Test
    void uiSaveAndApplyDraftViaForm() throws Exception {
        String path = "web/api/formtest/get.yml";
        String content = """
                version: tesseraql/v1
                id: formtest.list
                kind: route
                recipe: query-json
                sql:
                  file: formtest.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """;

        // post/redirect/get: saving redirects back to the source page with a status flag.
        HttpResponse<String> save = postForm("/_tesseraql/studio/ui/save",
                "path=" + enc(path) + "&content=" + enc(content));
        assertThat(save.statusCode()).isEqualTo(303);
        assertThat(save.headers().firstValue("location"))
                .hasValueSatisfying(value -> assertThat(value)
                        .startsWith("/_tesseraql/studio/ui/source?path=").contains("saved=1"));

        HttpResponse<String> afterSave = get(save.headers().firstValue("location").orElseThrow(),
                true);
        assertThat(afterSave.statusCode()).isEqualTo(200);
        assertThat(afterSave.body()).contains("Draft saved.");
        // The multi-line content round-trips into the editor textarea.
        assertThat(afterSave.body()).contains("formtest.list").contains("query-json");

        // The applied route is reloaded, so its referenced SQL file must exist and compile.
        Files.createDirectories(appHome.resolve("web/api/formtest"));
        Files.writeString(appHome.resolve("web/api/formtest/formtest.sql"), "select 1");

        HttpResponse<String> apply = postForm("/_tesseraql/studio/ui/apply", "path=" + enc(path));
        assertThat(apply.statusCode()).isEqualTo(303);
        HttpResponse<String> afterApply = get(apply.headers().firstValue("location").orElseThrow(),
                true);
        assertThat(afterApply.body()).contains("Draft applied and routes reloaded.");
    }

    @Test
    void wizardIndexAndFormRender() throws Exception {
        HttpResponse<String> index = get("/_tesseraql/studio/ui/wizard", true);
        assertThat(index.statusCode()).isEqualTo(200);
        assertThat(index.body()).contains("Setup wizards").contains("SAML SP");

        HttpResponse<String> form = get("/_tesseraql/studio/ui/wizard/saml", true);
        assertThat(form.statusCode()).isEqualTo(200);
        assertThat(form.body()).contains("SAML SP wizard").contains("name=\"acsUrl\"");
    }

    @Test
    void wizardSubmitDownloadsGeneratedConfig() throws Exception {
        String form = "spAudience=" + enc("https://app.example.com/saml")
                + "&acsUrl=" + enc("https://app.example.com/acs")
                + "&ssoUrl=" + enc("https://idp.example.com/sso")
                + "&loginIdAttribute=uid&provision=true"
                + "&publicKeyPath=" + enc("security/saml/idp-signing.pem");

        HttpResponse<String> result = postForm("/_tesseraql/studio/ui/wizard/saml", form);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(result.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/yaml"));
        assertThat(result.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"tesseraql-saml.yml\"");
        assertThat(result.body()).contains("audience: \"https://app.example.com/saml\"");
        assertThat(result.body()).contains("ssoUrl: \"https://idp.example.com/sso\"");
        assertThat(result.body()).contains("publicKey: \"security/saml/idp-signing.pem\"");
        assertThat(result.body()).contains("provision: true");
        // Optional fields left blank are omitted.
        assertThat(result.body()).doesNotContain("sloUrl");
    }

    @Test
    void wizardSubmitRejectsMissingRequiredField() throws Exception {
        HttpResponse<String> result = postForm("/_tesseraql/studio/ui/wizard/saml",
                "acsUrl=" + enc("https://app.example.com/acs"));

        assertThat(result.statusCode()).isEqualTo(400);
    }

    @Test
    void wizardRequiresAuthentication() throws Exception {
        assertThat(get("/_tesseraql/studio/ui/wizard", false).statusCode()).isEqualTo(401);
    }

    private static HttpResponse<String> postForm(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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

    private static HttpResponse<String> post(String path, String body, boolean auth)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth) {
            request.header("Authorization", "Bearer " + token());
        }
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String token() throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder
                .encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(
                MAPPER.writeValueAsBytes(Map.of("sub", "studio-user", "roles", List.of("ADMIN"))));
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
        Path target = Files.createTempDirectory("tesseraql-studio-it");
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

                tesseraql:
                  studio:
                    enabled: true
                    readOnly: false
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
