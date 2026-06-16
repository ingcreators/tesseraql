package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for mutual-TLS authentication of service callers (roadmap Phase 25): a route
 * declaring {@code auth: mtls} accepts a client certificate the TLS-terminating edge forwards in the
 * configured header (URL-encoded PEM), denies an unrecognized, expired, or missing certificate
 * (401), and applies the same authorization policy so an under-privileged certificate is forbidden
 * (403). TLS is terminated at the edge, so the test forwards the certificate as a header value.
 */
@Testcontainers
class MtlsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String HEADER = "ssl-client-cert";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
        seedDatabase();
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
    void acceptsRecognizedClientCertificate() throws Exception {
        // The billing certificate (CN=billing-service) maps to a principal holding USER_READ.
        assertThat(get(forwarded("client.pem")).statusCode()).isEqualTo(200);
    }

    @Test
    void forbidsCertificateWithoutRequiredRole() throws Exception {
        // The intruder certificate authenticates as the readonly client, which lacks USER_READ (403).
        assertThat(get(forwarded("rogue-client.pem")).statusCode()).isEqualTo(403);
    }

    @Test
    void rejectsExpiredCertificate() throws Exception {
        // Matches the expired client on identity, but its validity window has passed (401).
        assertThat(get(forwarded("expired-client.pem")).statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsMalformedCertificate() throws Exception {
        assertThat(get("not-a-certificate").statusCode()).isEqualTo(401);
    }

    @Test
    void rejectsMissingCertificate() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(URI.create("http://localhost:" + runtime.port() + "/api/mtls"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);
    }

    private static String forwarded(String pemResource) throws IOException {
        return URLEncoder.encode(pem(pemResource), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String headerValue) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest
                        .newBuilder(URI.create("http://localhost:" + runtime.port() + "/api/mtls"))
                        .header(HEADER, headerValue).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String pem(String resource) throws IOException {
        try (InputStream in = MtlsIntegrationTest.class.getResourceAsStream("/mtls/" + resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("insert into users (name, status) values ('a','ACTIVE')");
        }
    }

    private static Path prepareAppHome() throws Exception {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-mtls-it");
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
        // An mTLS block (billing carries USER_READ; readonly carries nothing; expired carries
        // USER_READ but its certificate has lapsed), injected ahead of the existing jwt block.
        String mtls = "    mtls:\n"
                + "      forwardedHeader: ssl-client-cert\n"
                + "      clients:\n"
                + "        billing:\n"
                + "          subjectDn: \"CN=billing-service,O=Acme\"\n"
                + "          subject: svc:billing\n"
                + "          roles: [USER_READ]\n"
                + "        readonly:\n"
                + "          subjectDn: \"CN=intruder,O=Evil\"\n"
                + "          subject: svc:readonly\n"
                + "        expired:\n"
                + "          subjectDn: \"CN=expired-service,O=Acme\"\n"
                + "          subject: svc:expired\n"
                + "          roles: [USER_READ]\n"
                + "    jwt:\n";
        Path config = target.resolve("config/tesseraql.yml");
        Files.writeString(config, Files.readString(config).replace("    jwt:\n", mtls));
        // A service route protected by a client certificate, reusing the example's users.read policy.
        Files.createDirectories(target.resolve("web/api/mtls"));
        Files.writeString(target.resolve("web/api/mtls/list.sql"),
                "select id, name from users order by id\n");
        Files.writeString(target.resolve("web/api/mtls/get.yml"), """
                version: tesseraql/v1
                id: mtls.list
                kind: route
                recipe: query-json
                security:
                  auth: mtls
                  policy: users.read
                sql:
                  file: list.sql
                  mode: query
                response:
                  json:
                    status: 200
                    body:
                      data: sql.rows
                """);
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
