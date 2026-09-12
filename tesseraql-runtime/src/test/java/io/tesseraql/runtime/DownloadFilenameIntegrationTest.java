package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A download keeps its name on the wire (docs/download-name-and-bytes.md). Every {@code
 * Content-Disposition} the framework writes goes through one helper, and until this the helper
 * put the name into {@code filename="…"} verbatim: Netty writes a header value one byte per
 * UTF-16 unit, so a Japanese name left as {@code ????.csv}, an emoji as two {@code ?}s, and a
 * Latin-1 letter as one raw octet that a Japanese-UI Chromium mis-decodes. The value is now
 * RFC 6266's two-parameter form for any non-ASCII name, and today's exact value for an ASCII one.
 *
 * <p>Every assertion is an exact header value. The JDK client decodes header bytes as
 * ISO-8859-1, a bijection on octets, so a {@code contains(original)} assertion is green on the
 * DEFECT for every U+0080..U+00FF name and red on the fix; only the exact ASCII value tells
 * them apart. Each fixture pins one code-point class a built one-edit variant of the helper gets
 * wrong: ASCII (must not change), a decomposable Latin-1 letter (the gate at U+007F), one with
 * no decomposition (the fallback's own bound), CJK ({@code filename*} itself), astral (code-point
 * iteration), a quote inside a non-ASCII name (both halves from the sanitized name), a C0 and a
 * bidi control (the fold, and the streamed shape that dropped the header on a control), a joiner
 * (kept), the route id as the default name, and the end user's own upload spelled composed,
 * decomposed and with a bidi override. HTTP/1.1 is pinned; the CJK row is repeated over h2c
 * because the HPACK path is a different encoder that converges on the same byte mangling.
 */
@Testcontainers
class DownloadFilenameIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";
    private static final HttpClient HTTP_1_1 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1).build();
    private static final HttpClient H2C = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2).build();

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void startRuntime() throws Exception {
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, 0);
        seedDatabase();
    }

    @AfterAll
    static void stopRuntime() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
    }

    @Test
    void anAsciiExportNameIsTheValueItAlwaysWas() throws Exception {
        assertThat(disposition("/api/dl/ascii")).isEqualTo("attachment; filename=\"orders.csv\"");
    }

    @Test
    void aLatin1ExportNameCarriesBothHalvesBecauseTheGateIsAsciiNotLatin1() throws Exception {
        String value = disposition("/api/dl/latin1");
        assertThat(value).isEqualTo(
                "attachment; filename=\"cafe.csv\"; filename*=UTF-8''caf%C3%A9.csv");
        // The reconstruction: what the client decoded is the octets that left the server.
        assertThat(value.chars().allMatch(c -> c < 0x80)).isTrue();
    }

    @Test
    void aJapaneseExportNameIsCarriedInFilenameStarOnBothTransports() throws Exception {
        String expected = "attachment; filename=\"____.csv\";"
                + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7.csv";
        assertThat(disposition("/api/dl/cjk")).isEqualTo(expected);
        HttpResponse<String> h2 = H2C.send(request("/api/dl/cjk", token(List.of("USER_READ")))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(h2.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(h2.headers().firstValue("content-disposition")).hasValue(expected);
    }

    @Test
    void anAstralCharacterIsOneCodePointNotTwoUnits() throws Exception {
        assertThat(disposition("/api/dl/astral")).isEqualTo(
                "attachment; filename=\"a_b.csv\"; filename*=UTF-8''a%F0%9F%98%80b.csv");
    }

    @Test
    void aLatin1LetterWithoutADecompositionFallsBackToAnUnderscoreNotARawByte() throws Exception {
        assertThat(disposition("/api/dl/ss")).isEqualTo(
                "attachment; filename=\"Stra_e.csv\"; filename*=UTF-8''Stra%C3%9Fe.csv");
    }

    @Test
    void aRouteIdIsTheDefaultNameAndIsSpelledTheSameWay() throws Exception {
        assertThat(disposition("/api/dl/default")).isEqualTo("attachment; filename=\"____.csv\";"
                + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%E5%87%BA%E5%8A%9B.csv");
    }

    @Test
    void aQuoteInsideANonAsciiNameReachesNeitherHalf() throws Exception {
        assertThat(disposition("/api/dl/quote")).isEqualTo("attachment; filename=\"_____.csv\";"
                + " filename*=UTF-8''%E5%8F%97%E6%B3%A8_%E4%B8%80%E8%A6%A7.csv");
    }

    /**
     * A C0 control used to reach Vert.x, which dropped the header from the streamed response
     * (a 200 with no Content-Disposition); presence is asserted by equality. A bidi override is
     * folded before either half, so the name is ASCII and single-form.
     */
    @Test
    void aControlOrBidiCharacterIsFoldedBeforeTheWire() throws Exception {
        assertThat(disposition("/api/dl/esc")).isEqualTo("attachment; filename=\"a_b.csv\"");
        assertThat(disposition("/api/dl/bidi")).isEqualTo("attachment; filename=\"inv_gpj.exe\"");
        // The Arabic letter mark is the Bidi_Control member every enumerated fold list missed.
        assertThat(disposition("/api/dl/alm")).isEqualTo("attachment; filename=\"inv_gpj.exe\"");
    }

    @Test
    void aJoinerInsideAWordSurvivesToTheEncodedHalf() throws Exception {
        assertThat(disposition("/api/dl/persian")).isEqualTo(
                "attachment; filename=\"________.csv\"; filename*=UTF-8''"
                        + "%D9%85%DB%8C%E2%80%8C%D8%AE%D9%88%D8%A7%D9%87%D9%85.csv");
    }

    @Test
    void aSplitExportNamesItsBundleAfterTheStemWithoutADanglingDash() throws Exception {
        HttpResponse<String> response = HTTP_1_1.send(
                request("/api/dl/bundle", token(List.of("USER_READ"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/zip");
        assertThat(response.headers().firstValue("content-disposition")).hasValue(
                "attachment; filename=\"__.zip\"; filename*=UTF-8''%E5%8F%97%E6%B3%A8.zip");
    }

    @Test
    void anUploadedNameComesBackOnTheDownloadAndOnTheListing() throws Exception {
        String writer = token(List.of("USER_WRITE"));
        // Netty's multipart decoder strips " and \\, folds ; = , : TAB and DEL to a space, and
        // passes a bidi override, a C1 control and a combining mark into the stored name — so
        // the end user's own upload is what the bidi and decomposed rows drive.
        for (String[] row : new String[][]{
                {"請求書.pdf", "attachment; filename=\"___.pdf\";"
                        + " filename*=UTF-8''%E8%AB%8B%E6%B1%82%E6%9B%B8.pdf"},
                {"café.pdf",
                        "attachment; filename=\"cafe.pdf\"; filename*=UTF-8''caf%C3%A9.pdf"},
                {"cafe\u0301.pdf",
                        "attachment; filename=\"cafe.pdf\"; filename*=UTF-8''cafe%CC%81.pdf"},
                {"請求\u202Dexe.pdf", "attachment; filename=\"___exe.pdf\";"
                        + " filename*=UTF-8''%E8%AB%8B%E6%B1%82_exe.pdf"},
                {"Bjørn.pdf",
                        "attachment; filename=\"Bj_rn.pdf\"; filename*=UTF-8''Bj%C3%B8rn.pdf"},
                {"a\u0092b.pdf", "attachment; filename=\"a_b.pdf\""}}) {
            HttpResponse<String> uploaded = upload("/reports/R-1/files", writer, row[0],
                    "%PDF-1.4 stub".getBytes(StandardCharsets.UTF_8));
            assertThat(uploaded.statusCode()).isEqualTo(201);
            String id = MAPPER.readTree(uploaded.body()).path("id").asText();

            HttpResponse<String> download = HTTP_1_1.send(
                    request("/reports/R-1/files/" + id, writer).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(download.statusCode()).isEqualTo(200);
            assertThat(download.headers().firstValue("content-disposition")).hasValue(row[1]);

            // The listing is the store's control: the row keeps the name the header encodes.
            JsonNode listing = MAPPER.readTree(HTTP_1_1.send(
                    request("/reports/R-1/files", writer).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertThat(listing.findValues("filename")).extracting(JsonNode::asText)
                    .contains(row[0]);
        }
    }

    @Test
    void aJobExportReServedByTheOpsConsoleKeepsItsName() throws Exception {
        String operator = token(List.of("BATCH_OPERATOR"));
        HttpResponse<String> run = HTTP_1_1.send(
                request("/_tesseraql/ops/batch/jobs/user.exportNamed/run", operator)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"businessDate\":"
                                + " \"2026-03-31\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(run.body()).contains("COMPLETED");
        String transferId = transferIdOf("user.exportNamed#extract");

        HttpResponse<String> file = HTTP_1_1.send(
                request("/_tesseraql/ops/batch/transfers/" + transferId + "/file", operator)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.headers().firstValue("content-disposition")).hasValue(
                "attachment; filename=\"______-2026-03-31.csv\";"
                        + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%E3%83%AC%E3%83%9D%E3%83%BC"
                        + "%E3%83%88-2026-03-31.csv");
    }

    private static String disposition(String path) throws Exception {
        HttpResponse<String> response = HTTP_1_1.send(
                request(path, token(List.of("USER_READ"))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        HttpHeaders headers = response.headers();
        return headers.firstValue("content-disposition").orElseThrow();
    }

    private static HttpRequest.Builder request(String path, String bearer) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + bearer);
    }

    private static HttpResponse<String> upload(String path, String bearer, String filename,
            byte[] bytes) throws Exception {
        String boundary = "dl-filename-boundary";
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\";"
                + " filename=\"" + filename + "\"\r\nContent-Type: application/pdf"
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HTTP_1_1.send(request(path, bearer)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token(List<String> roles) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "u001", "preferred_username", "sato", "roles", roles,
                        "permissions", List.of("tql.ops.view.*", "tql.ops.run.*")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static String transferIdOf(String routeId) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "select transfer_id from tql_file_transfer where route_id = '"
                                + routeId + "' order by created_at desc")) {
            assertThat(rs.next()).as("a transfer row for " + routeId).isTrue();
            return rs.getString(1);
        }
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table users restart identity");
            statement.execute("""
                    insert into users (name, status) values
                      ('sato', 'ACTIVE'),
                      ('suzuki', 'ACTIVE'),
                      ('tanaka', 'INACTIVE')""");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-dl-name");
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

        // The four ranges, one query-export route each, over the example's own users table.
        for (String[] route : new String[][]{
                {"ascii", "orders.csv"}, {"latin1", "café.csv"}, {"cjk", "受注一覧.csv"},
                {"astral", "a😀b.csv"}, {"ss", "Straße.csv"},
                {"quote", "受注\\\"一覧.csv"}, {"esc", "a\\u001Bb.csv"},
                {"bidi", "inv\\u202Egpj.exe"}, {"alm", "inv\\u061Cgpj.exe"},
                {"persian", "می\\u200Cخواهم.csv"}}) {
            Path dir = target.resolve("web/api/dl/" + route[0]);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("get.yml"), """
                    version: tesseraql/v1
                    id: dl.%s
                    kind: route
                    recipe: query-export
                    security:
                      policy: users.read
                    sources:
                      main:
                        sql:
                          file: names.sql
                    export:
                      format: csv
                      filename: "%s"
                      columns:
                        - { name: name, label: Name }
                    """.formatted(route[0], route[1]));
            Files.writeString(dir.resolve("names.sql"), "select name from users order by name\n");
        }
        // The documented default: a route with no filename: is named after its id.
        Path byId = target.resolve("web/api/dl/default");
        Files.createDirectories(byId);
        Files.writeString(byId.resolve("get.yml"), """
                version: tesseraql/v1
                id: 受注出力
                kind: route
                recipe: query-export
                security:
                  policy: users.read
                sources:
                  main:
                    sql:
                      file: names.sql
                export:
                  format: csv
                  columns:
                    - { name: name, label: Name }
                """);
        Files.writeString(byId.resolve("names.sql"), "select name from users order by name\n");

        // A split export: the bundle's name comes from the stem, so the placeholder's dash
        // must not survive into it.
        Path bundle = target.resolve("web/api/dl/bundle");
        Files.createDirectories(bundle);
        Files.writeString(bundle.resolve("get.yml"), """
                version: tesseraql/v1
                id: dl.bundle
                kind: route
                recipe: query-export
                security:
                  policy: users.read
                sources:
                  main:
                    sql:
                      file: by-status.sql
                export:
                  format: csv
                  filename: "受注-{key}.csv"
                  splitBy: status
                  columns:
                    - { name: name, label: Name }
                """);
        Files.writeString(bundle.resolve("by-status.sql"),
                "select status, name from users order by status, name\n");

        // The headline path: the inventory gallery's own attachment declaration, verbatim
        // but for its policy, which is bound to this host app's users.write. Loud if the
        // gallery's file drifts away from the shape this test reads.
        Path attachments = target.resolve("attachments");
        Files.createDirectories(attachments);
        String reports = Files.readString(Paths.get("..", "examples", "inventory-app",
                "attachments", "reports.yml"));
        if (!reports.contains("policy: inv.write")
                || !reports.contains("basePath: /reports/{reportId}/files")) {
            throw new IllegalStateException("examples/inventory-app/attachments/reports.yml"
                    + " changed shape; update DownloadFilenameIntegrationTest");
        }
        Files.writeString(attachments.resolve("reports.yml"),
                reports.replace("policy: inv.write", "policy: users.write"));

        // A job export whose declared name is Japanese, re-served by the operations console.
        Files.createDirectories(target.resolve("batch/named"));
        Files.writeString(target.resolve("batch/named/job.yml"), """
                version: tesseraql/v1
                id: user.exportNamed
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    export:
                      format: csv
                      filename: "受注レポート-{batch.businessDate}.csv"
                      columns:
                      - { name: name, label: Name }
                    sql:
                      file: report.sql
                      mode: query
                """);
        Files.writeString(target.resolve("batch/named/report.sql"),
                "select name from users order by name\n");
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
}
