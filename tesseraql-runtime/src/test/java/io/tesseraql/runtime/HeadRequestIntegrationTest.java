package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A HEAD is a GET without the body (RFC 9110 §9.3.2; docs/edge-hygiene.md E4): every GET
 * route, asset and health probe answers a HEAD with the GET's status and headers and no
 * content. Before, the router matched the route file's method alone and every one of them
 * answered 405 — on the direct leg and through the gateway alike, which is why the gateway
 * differential was green on the defect.
 */
@Testcontainers
class HeadRequestIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /**
     * Two clients: the JDK's default upgrades to h2c, where Vert.x's own response withholds
     * nothing on a HEAD; HTTP/1.1 is where it withholds the body but claims no length. The
     * edge answers both alike.
     */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static final HttpClient HTTP_1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
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
    void aHeadOfAJsonRouteAnswersTheGetsHeadersAndNoBody() throws Exception {
        HttpResponse<byte[]> get = send("GET", "/api/ping", null);
        HttpResponse<byte[]> head = send("HEAD", "/api/ping", null);

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(head.version()).as("the JDK client upgraded to h2c")
                .isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(head.statusCode()).as(new String(head.body())).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Type"))
                .isEqualTo(get.headers().firstValue("Content-Type"));
        // The length of the content the GET carries, as RFC 9110 lets a HEAD say.
        assertThat(head.headers().firstValueAsLong("Content-Length"))
                .hasValue(get.body().length);
    }

    /** The same over HTTP/1.1, where the transport's own withholding used to claim no length. */
    @Test
    void aHeadOfAJsonRouteAnswersTheSameOverHttp11() throws Exception {
        HttpResponse<byte[]> get = send(HTTP_1, "GET", "/api/ping", null);
        HttpResponse<byte[]> head = send(HTTP_1, "HEAD", "/api/ping", null);

        assertThat(head.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValueAsLong("Content-Length"))
                .hasValue(get.body().length);
    }

    @Test
    void aHeadOfAPageAnswersTextHtmlAndNoBody() throws Exception {
        HttpResponse<byte[]> get = send("GET", "/users", null);
        HttpResponse<byte[]> head = send("HEAD", "/users", null);

        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(head.statusCode()).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
        assertThat(head.headers().firstValueAsLong("Content-Length"))
                .hasValue(get.body().length);
    }

    /**
     * The two browser-navigation predicates treat a HEAD as the GET it is: the HEAD of a
     * protected page answers the 302 its GET answers, {@code Location} included — not the 401
     * its GET never gives a browser.
     */
    @Test
    void aHeadOfAProtectedPageAnswersTheGetsRedirect() throws Exception {
        HttpResponse<byte[]> get = send("GET", "/users/board", "text/html");
        HttpResponse<byte[]> head = send("HEAD", "/users/board", "text/html");

        assertThat(get.statusCode()).isEqualTo(302);
        assertThat(head.statusCode()).isEqualTo(302);
        assertThat(head.headers().firstValue("Location"))
                .isEqualTo(get.headers().firstValue("Location"));
        assertThat(head.body()).isEmpty();
    }

    @Test
    void aHeadOfAnAssetAndOfAHealthProbeAnswers() throws Exception {
        String page = new String(send("GET", "/users", null).body());
        Matcher asset = Pattern.compile("(/assets/[^\"' ]+\\.(?:css|js))").matcher(page);
        assertThat(asset.find()).as("the page links an asset").isTrue();
        HttpResponse<byte[]> get = send("GET", asset.group(1), null);
        HttpResponse<byte[]> head = send("HEAD", asset.group(1), null);
        assertThat(get.statusCode()).as(asset.group(1)).isEqualTo(200);
        assertThat(head.statusCode()).as(asset.group(1)).isEqualTo(200);
        assertThat(head.body()).isEmpty();
        assertThat(head.headers().firstValueAsLong("Content-Length"))
                .hasValue(get.body().length);

        HttpResponse<byte[]> live = send("HEAD", "/_tesseraql/health/live", null);
        assertThat(live.statusCode()).isEqualTo(200);
        assertThat(live.body()).isEmpty();
    }

    /**
     * A HEAD reaches what a GET reaches and nothing else: a POST-only path answers a HEAD as
     * it answers a GET (no route), and the event stream — GET-only by decision, a stream has
     * nothing to head — is not answered.
     */
    @Test
    void aHeadOfAPostRouteAndOfTheEventStreamIsNotAnswered() throws Exception {
        HttpResponse<byte[]> get = send("GET", "/api/users/provision", null);
        HttpResponse<byte[]> head = send("HEAD", "/api/users/provision", null);
        assertThat(get.statusCode()).isIn(404, 405);
        assertThat(head.statusCode()).isEqualTo(get.statusCode());

        assertThat(send("HEAD", "/_tesseraql/events", null).statusCode()).isIn(404, 405);
    }

    private static HttpResponse<byte[]> send(String method, String path, String accept)
            throws Exception {
        return send(HTTP, method, path, accept);
    }

    private static HttpResponse<byte[]> send(HttpClient client, String method, String path,
            String accept) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (accept != null) {
            request.header("Accept", accept);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-head-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        UserAdminAppJobs.parkDailyMaintenanceSchedule(target);
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
        Path ping = target.resolve("web/api/ping");
        Files.createDirectories(ping);
        Files.writeString(ping.resolve("get.yml"), """
                version: tesseraql/v1
                id: ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: ping.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(ping.resolve("ping.sql"), "select 1 as ok\n");
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
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
