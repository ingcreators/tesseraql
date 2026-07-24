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
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Milestone M23 (docs/roadmap.md, Phase 58) against the REAL inventory gallery app: the DuckDB
 * driver rides {@code tesseraql.modules}; the {@code postgres} extension is provisioned from an
 * air-gap style bundle (built connected, unzipped into the cache — the runtime itself never
 * fetches); a dashboard composes a {@code main} widget beside a tenant's Parquet drop through a
 * tenant-partitioned scope, and another tenant cannot reach it; the nightly pricing job joins the
 * drop against the attached {@code main} and lands a summary, safe to re-run; and a Parquet
 * report uploaded through the app's attachment route is queryable back through {@code ${dataset}}
 * by its owner only.
 */
@Testcontainers
class InventoryAnalyticsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    TesseraqlRuntime runtime;
    Path appHome;

    @AfterEach
    void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void meetsMilestoneM23OnTheInventoryApp() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The tenant-scoped dashboard: a main widget beside the tenant's own Parquet drop.
        String alphaBoard = get("/tenantboard", Map.of("X-Tenant-Id", "alpha")).body();
        assertThat(alphaBoard).contains("Monthly drop");
        assertThat(alphaBoard).contains("142.5"); // alpha's drop
        assertThat(alphaBoard).doesNotContain("77.7"); // never beta's

        String betaBoard = get("/tenantboard", Map.of("X-Tenant-Id", "beta")).body();
        assertThat(betaBoard).contains("77.7");
        assertThat(betaBoard).doesNotContain("142.5");

        // The nightly pricing job, twice: the summary lands on main and converges.
        for (int run = 0; run < 2; run++) {
            assertThat(runtime.runJob("pricing.loadSummary", Map.of()).status().name())
                    .isEqualTo("COMPLETED");
        }
        try (Connection pg = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = pg.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select sku, best_price, suppliers from price_summary order by sku")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("GADGET-7");
            assertThat(rs.getBigDecimal(2)).isEqualByComparingTo("6.80");
            assertThat(rs.getInt(3)).isEqualTo(2);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("WIDGET-1");
            assertThat(rs.next()).isFalse();
        }

        // A Parquet report uploaded through the app's own attachment route (the blob store
        // write path) is queryable back through ${dataset.*} — by its owner only.
        String uploader = jwt("user-a", "INV_READ", "INV_WRITE");
        String other = jwt("user-b", "INV_READ");
        byte[] parquet = Files.readAllBytes(appHome.resolve("data/drops/alpha/monthly.parquet"));
        HttpResponse<String> uploaded = upload("/reports/R-1/files", uploader,
                "report.parquet", parquet);
        assertThat(uploaded.statusCode()).isEqualTo(201);
        String datasetId = MAPPER.readTree(uploaded.body()).get("id").asText();

        HttpResponse<String> owned = get("/api/report?id=" + datasetId,
                Map.of("Authorization", "Bearer " + uploader));
        assertThat(owned.statusCode()).isEqualTo(200);
        assertThat(owned.body()).contains("widgets");

        assertThat(get("/api/report?id=" + datasetId,
                Map.of("Authorization", "Bearer " + other)).statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path));
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> upload(String path, String bearer, String filename, byte[] bytes)
            throws Exception {
        String boundary = "m23boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"" + filename + "\"\r\nContent-Type: application/octet-stream"
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + bearer)
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-inventory-m23");
        Path gallery = Path.of("..", "examples", "inventory-app").toAbsolutePath().normalize();
        copyRecursively(gallery, home);

        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tenancy:
                  enabled: true
                  mode: shared-schema
                  required: false
                  resolver:
                    type: header
                    source: X-Tenant-Id
                  registry:
                    sql: select tenant_id from tenants order by tenant_id
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        // Overlay pieces the single-tenant gallery app does not carry: the tenants table and a
        // tenant-scoped dashboard over the tenantDrops scope.
        Files.writeString(home.resolve("db/migration/V90__tenants.sql"),
                "create table tenants (tenant_id varchar(32) primary key);\n"
                        + "insert into tenants (tenant_id) values ('alpha'), ('beta');\n");
        Path board = home.resolve("web/tenantboard");
        Files.createDirectories(board);
        Files.writeString(board.resolve("get.yml"), """
                version: tesseraql/v1
                id: tenantboard.view
                kind: route
                recipe: query-html
                # Deliberately open: this fixture tests analytics reads, not authentication,
                # and the gallery config now declares security defaults that would otherwise
                # require a session here.
                security:
                  auth: public
                sql:
                  file: totals.sql
                  mode: query
                queries:
                  drop:
                    file: drop.sql
                    datasource: analytics
                response:
                  html:
                    status: 200
                    view: tenantboard.view.yml
                """);
        Files.writeString(board.resolve("totals.sql"),
                "select count(*) as products from products\n");
        Files.writeString(board.resolve("drop.sql"), """
                select category, sum(total) as total
                from read_parquet(/* ${scope.tenantDrops}/monthly.parquet */ 'dummy.parquet')
                group by category
                """);
        Files.writeString(board.resolve("tenantboard.view.yml"), """
                version: tesseraql/v1
                id: tenantboard.page
                kind: view
                view: dashboard
                title: Tenant analytics
                panels:
                  - { type: stat, source: sql, column: products, label: Products }
                  - type: table
                    source: drop
                    title: Monthly drop
                    columns:
                      - { name: category }
                      - { name: total }
                """);

        // Air-gap extension provisioning: INSTALL into a connected cache, bundle it as a zip,
        // unzip into the app's cache — exactly the `--bundle`/`--from-bundle` flow; the runtime
        // itself never fetches (autoinstall/autoload are off on every connection).
        Path connected = Files.createTempDirectory("duckdb-connected-cache");
        Properties props = new Properties();
        props.setProperty("extension_directory", connected.toString());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:", props);
                Statement statement = duck.createStatement()) {
            statement.execute("INSTALL postgres");
            statement.execute("INSTALL ducklake");
        }
        Path bundle = Files.createTempDirectory("duckdb-bundle").resolve("duckdb-ext.zip");
        zipDirectory(connected, bundle);
        unzipInto(bundle, home.resolve("work/duckdb-extensions"));
        deleteRecursively(connected);

        // Two tenants' Parquet drops under the same scope root.
        writeDrop(home.resolve("data/drops/alpha/monthly.parquet"), 142.5);
        writeDrop(home.resolve("data/drops/beta/monthly.parquet"), 77.7);
        return home;
    }

    private static void writeDrop(Path target, double total) throws Exception {
        Files.createDirectories(target.getParent());
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = duck.createStatement()) {
            statement.execute("""
                    COPY (SELECT 'widgets' AS category, %s AS total)
                    TO '%s' (FORMAT parquet)
                    """.formatted(total, target));
        }
    }

    private static String jwt(String subject, String... roles) throws Exception {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        StringBuilder roleList = new StringBuilder();
        for (String role : roles) {
            roleList.append(roleList.isEmpty() ? "" : ",").append('"').append(role).append('"');
        }
        String payload = base64Url("{\"sub\":\"" + subject + "\",\"roles\":[" + roleList
                + "],\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
        return header + "." + payload + "." + signature;
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void zipDirectory(Path directory, Path zip) throws IOException {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip));
                Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                        directory.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    private static void unzipInto(Path zip, Path directory) throws IOException {
        try (var in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
            for (var entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                Path target = directory.resolve(entry.getName()).normalize();
                if (!target.startsWith(directory)) {
                    throw new IOException("zip entry escapes target: " + entry.getName());
                }
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyRecursively(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path source : files.toList()) {
                Path relative = from.relativize(source);
                if (relative.startsWith("work") || relative.startsWith("tests")) {
                    continue; // the module cache and the app's own sql suite stay behind
                }
                Path target = to.resolve(relative.toString());
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
