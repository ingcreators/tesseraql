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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for mounted apps (design ch. 32): a second app listed under
 * {@code tesseraql.apps.<name>.path} is compiled by the same route compiler and served alongside
 * the main app, sharing its datasources.
 */
@Testcontainers
class MountedAppIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path mountedHome;

    @BeforeAll
    static void start() throws Exception {
        mountedHome = prepareMountedApp();
        appHome = prepareAppHome(mountedHome);
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        for (Path root : new Path[]{appHome, mountedHome}) {
            if (root != null) {
                deleteRecursively(root);
            }
        }
    }

    @Test
    void mountedAppRouteIsServed() throws Exception {
        HttpResponse<String> response = get("/sysapp/ping");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(response.body()).get("data").get(0).get("ok").asInt())
                .isEqualTo(1);
    }

    @Test
    void namedQueriesBindEachResultSet() throws Exception {
        HttpResponse<String> response = get("/sysapp/multi?n=42");

        assertThat(response.statusCode()).isEqualTo(200);
        var json = MAPPER.readTree(response.body());
        assertThat(json.get("first").get(0).get("a").asInt()).isEqualTo(1);
        // The named query received its bind from the request input.
        assertThat(json.get("second").get(0).get("b").asInt()).isEqualTo(42);
    }

    @Test
    void commandRouteRedirectsAfterPost() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/sysapp/touch"))
                .POST(HttpRequest.BodyPublishers.ofString("{\"n\": 9}"))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(303);
        assertThat(response.headers().firstValue("location")).hasValue("/sysapp/multi?n=9");
    }

    @Test
    void pageRouteRendersTemplateWithoutSql() throws Exception {
        HttpResponse<String> response = get("/sysapp/hello?who=tessera");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/html"));
        assertThat(response.body()).contains("Hello tessera");
    }

    @Test
    void fileRouteServesGeneratedDownload() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/sysapp/conf"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("appName=demo-app"))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/yaml"));
        assertThat(response.headers().firstValue("content-disposition"))
                .hasValue("attachment; filename=\"app-config.yml\"");
        assertThat(response.body()).contains("name: \"demo-app\"");
    }

    @Test
    void servesMainAppAssets() throws Exception {
        HttpResponse<String> response = get("/assets/site.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/css"));
        assertThat(response.headers().firstValue("etag")).isPresent();
        assertThat(response.headers().firstValue("cache-control")).isPresent();
        assertThat(response.body()).contains(".main");

        // Conditional revalidation answers 304.
        HttpRequest conditional = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/assets/site.css"))
                .header("If-None-Match", response.headers().firstValue("etag").orElseThrow())
                .build();
        assertThat(HttpClient.newHttpClient()
                .send(conditional, HttpResponse.BodyHandlers.ofString()).statusCode())
                .isEqualTo(304);
    }

    @Test
    void servesMountedAppAssetsUnderAppName() throws Exception {
        HttpResponse<String> response = get("/assets/sysapp/app.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(".sysapp");
    }

    @Test
    void servesFrameworkThemeCss() throws Exception {
        HttpResponse<String> response = get("/assets/_tesseraql/tesseraql.css");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type"))
                .hasValueSatisfying(value -> assertThat(value).contains("text/css"));
        assertThat(response.body()).contains("h2{font-size");
    }

    @Test
    void rejectsTraversalAndUnknownExtensions() throws Exception {
        assertThat(get("/assets/../config/application.yml").statusCode()).isEqualTo(404);
        assertThat(get("/assets/site.exe").statusCode()).isEqualTo(404);
        assertThat(get("/assets/.hidden.css").statusCode()).isEqualTo(404);
    }

    @Test
    void exampleAppServesShellReferencePage() throws Exception {
        // The example app's user page composes the framework hc-shell fragment with its own
        // sidebar navigation and loads the server-rendered table fragment with htmx.
        HttpResponse<String> response = get("/users");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("hc-shell");
        assertThat(response.body()).contains("Users");
        assertThat(response.body()).contains("hx-get=\"/users/fragments/table\"");
        // Live search (the htmx active-search recipe) re-fetches the fragment as you type.
        assertThat(response.body()).contains("hx-trigger=\"input changed delay:300ms, search\"");
        // The shared app nav (templates/nav.html fragment) replaces the system-console nav.
        assertThat(response.body()).contains("User Management (IAM)");
        assertThat(response.body()).doesNotContain("/_tesseraql/ops/console");
    }

    @Test
    void mainAppRoutesStillWork() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + "/api/users"))
                .header("Authorization", "Bearer " + token())
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        java.util.Base64.Encoder encoder = java.util.Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(
                "{\"alg\":\"HS256\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(MAPPER.writeValueAsBytes(
                java.util.Map.of("sub", "tester", "roles", java.util.List.of("USER_READ"))));
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "dev-only-secret-change-me-in-production"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = encoder.encodeToString(
                mac.doFinal((header + "." + payload)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void seedDatabase() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values ('sato','ACTIVE')");
        }
    }

    private static Path prepareMountedApp() throws IOException {
        Path home = Files.createTempDirectory("tesseraql-mounted-app");
        Path routeDir = home.resolve("web/sysapp/ping");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.ping
                kind: route
                recipe: query-json
                sql:
                  file: ping.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(routeDir.resolve("ping.sql"), "select 1 as ok\n;\n");

        // A route with an additional named query (the queries: block) whose params come from
        // the request, rendering two result sets in one response.
        Path multiDir = home.resolve("web/sysapp/multi");
        Files.createDirectories(multiDir);
        Files.writeString(multiDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.multi
                kind: route
                recipe: query-json
                input:
                  n:
                    type: integer
                    default: 7
                sql:
                  file: one.sql
                  mode: query
                queries:
                  second:
                    file: two.sql
                    mode: query
                    params:
                      n: query.n
                response:
                  json:
                    body:
                      first: sql.rows
                      second: second.rows
                """);
        Files.writeString(multiDir.resolve("one.sql"), "select 1 as a\n;\n");
        Files.writeString(multiDir.resolve("two.sql"), "select /* n */ 0 as b\n;\n");

        // A command route answering with a post/redirect/get redirect whose location resolves a
        // placeholder from the request.
        Path touchDir = home.resolve("web/sysapp/touch");
        Files.createDirectories(touchDir);
        Files.writeString(touchDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: sysapp.touch
                kind: route
                recipe: command-json
                input:
                  n:
                    type: integer
                    default: 5
                sql:
                  file: touch.sql
                  mode: update
                  params:
                    n: query.n
                response:
                  redirect:
                    location: /sysapp/multi?n={params.n}
                """);
        Files.writeString(touchDir.resolve("touch.sql"),
                "update users set status = status where id = /* n */ -1\n;\n");

        // A page route: template rendering without any data binding (forms, static pages).
        Path helloDir = home.resolve("web/sysapp/hello");
        Files.createDirectories(helloDir);
        Files.writeString(helloDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sysapp.hello
                kind: route
                recipe: page
                input:
                  who:
                    type: string
                    default: world
                response:
                  html:
                    template: hello.html
                    model:
                      who: params.who
                """);
        Files.createDirectories(home.resolve("templates"));
        Files.writeString(home.resolve("templates/hello.html"),
                "<!DOCTYPE html>\n<html><body><h1>Hello [[${who}]]</h1></body></html>\n");

        // A file route: a template-generated text download built from the request inputs.
        Path confDir = home.resolve("web/sysapp/conf");
        Files.createDirectories(confDir);
        Files.writeString(confDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: sysapp.conf
                kind: route
                recipe: page
                input:
                  appName:
                    type: string
                    required: true
                response:
                  file:
                    template: conf.yml.tpl
                    contentType: text/yaml
                    filename: app-config.yml
                    model:
                      appName: params.appName
                """);
        Files.writeString(home.resolve("templates/conf.yml.tpl"), """
                tesseraql:
                  app:
                    name: "[(${appName})]"
                """);

        // Static assets served under /assets/<app-name>/ for mounted apps.
        Files.createDirectories(home.resolve("assets"));
        Files.writeString(home.resolve("assets/app.css"), ".sysapp{color:#fff}\n");
        return home;
    }

    private static Path prepareAppHome(Path mounted) throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mounted-it");
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
                  apps:
                    sysapp:
                      path: %s
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), mounted));
        Files.createDirectories(target.resolve("assets"));
        Files.writeString(target.resolve("assets/site.css"), ".main{color:#000}\n");
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
