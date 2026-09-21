package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The procurement demo's exchange end to end (docs/procurement-documents-and-edi.md decisions 2,
 * 4 and 7): one {@link MultiAppHost} over both gallery members — {@code procurement-app} pushing
 * its receipt notice, {@code supplier-edi-app} polling for it — and one in-process SFTP server
 * between them, each side pinning the server's host key. The buyer's run lands the file, the
 * companion's poll imports it, its list shows the delivery note, and the buyer's rerun of the
 * same name re-delivers a file the companion imports again — the upsert updates the row it
 * wrote, so the list never duplicates one. A file the framework did not write — one opening with a
 * byte-order mark — is the measurement the record filed: what {@code file-import} makes of it.
 */
@Testcontainers
class SupplierEdiIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";
    private static final String ORDER = "ORD-S4-EDI";

    static MultiAppHost host;
    static Path installRoot;
    static Path sftpRoot;
    static SshServer sshd;
    static String pinned;

    @BeforeAll
    static void start() throws Exception {
        sftpRoot = Files.createTempDirectory("tesseraql-edi-drop");
        Files.createDirectories(sftpRoot.resolve("drop"));
        startSftpServer();
        try (Connection connection = connect(null);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema buyer");
            statement.execute("create schema supplier");
        }
        installRoot = Files.createTempDirectory("tesseraql-edi-stack");
        installApp("procurement", "procurement-app", "buyer");
        installApp("supplier-edi", "supplier-edi-app", "supplier");
        Files.writeString(installRoot.resolve(
                io.tesseraql.operations.app.StackSettings.FILE_NAME),
                """
                        framework:
                          datasource:
                            jdbcUrl: %s
                            username: %s
                            password: %s
                        """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                        POSTGRES.getPassword()));
        host = MultiAppHost.start(installRoot);
        // A received shipment on the buyer's side, as the tour leaves one.
        execute("buyer", "insert into orders (id, rfq_id, quote_id, partner_id, total_amount,"
                + " is_lowest, delta_pct, ordered_by) values (?, 'RFQ-2002', 'Q-RFQ-2002-P-200',"
                + " 'P-200', 618000.00, true, 0, 'hara')", ORDER);
        execute("buyer", "insert into shipments (order_id, ship_date, carrier, delivery_note_no,"
                + " shipped_by, received_at, received_by) values (?, date '2026-09-25',"
                + " 'ヤマト運輸', 'DN-2026-001', 'minami', timestamp '2026-09-21 10:30:00',"
                + " 'sato')", ORDER);
    }

    @AfterAll
    static void stop() throws IOException {
        if (host != null) {
            host.close();
        }
        if (sshd != null) {
            sshd.stop(true);
        }
        deleteRecursively(installRoot);
        deleteRecursively(sftpRoot);
    }

    @Test
    void theNoticeCrossesFromTheBuyerToTheSupplierAndARerunUpdatesNotDuplicates() throws Exception {
        HttpResponse<String> run = send("procurement",
                "/procurement/_tesseraql/ops/batch/jobs/edi.receiptNotice/run",
                "{\"businessDate\": \"2026-09-21\"}", opsToken());
        assertThat(run.body()).as(run.body()).contains("COMPLETED");
        assertThat(sftpRoot.resolve("drop/receipt-notice-2026-09-21.csv")).exists();

        JsonNode imported = awaitNotice("DN-2026-001");
        assertThat(imported.get("order_id").asText()).isEqualTo(ORDER);
        assertThat(imported.get("partner_name").asText()).isEqualTo("ミナミオフィスサプライ株式会社");
        assertThat(imported.get("carrier").asText()).isEqualTo("ヤマト運輸");
        String importedAt = imported.get("imported_at").asText();
        // The consumer moved the file out of the drop once it was ingested.
        assertThat(sftpRoot.resolve("drop/receipt-notice-2026-09-21.csv")).doesNotExist();
        assertThat(sftpRoot.resolve("drop/.done/receipt-notice-2026-09-21.csv")).exists();

        // The buyer reruns the feed: the same name, the same bytes, a new upload — so a new
        // modified time, which is a new file to the consume-once claim (name, size and
        // modified time; docs/connectors.md). The companion imports it again, and the upsert
        // updates the row it wrote: a later import time, the same count. Never a duplicate.
        HttpResponse<String> rerun = send("procurement",
                "/procurement/_tesseraql/ops/batch/jobs/edi.receiptNotice/run",
                "{\"businessDate\": \"2026-09-21\"}", opsToken());
        assertThat(rerun.body()).contains("COMPLETED");
        long deadline = System.currentTimeMillis() + 30_000;
        JsonNode again = notice("DN-2026-001");
        while (again.get("imported_at").asText().equals(importedAt)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(500);
            again = notice("DN-2026-001");
        }
        assertThat(again.get("imported_at").asText()).isNotEqualTo(importedAt);
        assertThat(again.get("order_id").asText()).isEqualTo(ORDER);
        assertThat(notices().size()).isEqualTo(2); // the seeded notice and the imported one
    }

    /**
     * The record's filed measurement (docs/procurement-documents-and-edi.md, "Filed, not
     * fixed"): a file opening with a UTF-8 byte-order mark, as a spreadsheet or a buyer's
     * {@code bom: true} export writes one. The mark must not become part of the first column,
     * or the first column's value never binds and the row is lost.
     */
    @Test
    void aFileOpeningWithAByteOrderMarkImportsItsFirstColumn() throws Exception {
        String csv = "﻿delivery_note_no,order_id,partner_id,partner_name,ship_date,carrier,"
                + "received_at\nDN-2026-002,ORD-BOM,P-200,ミナミオフィスサプライ株式会社,2026-09-26,"
                + "ヤマト運輸,2026-09-22 08:00:00\n";
        Files.writeString(sftpRoot.resolve("drop/receipt-notice-2026-09-22.csv"), csv,
                StandardCharsets.UTF_8);

        JsonNode imported = awaitNotice("DN-2026-002");
        assertThat(imported.get("order_id").asText()).isEqualTo("ORD-BOM");
    }

    private static JsonNode awaitNotice(String deliveryNoteNo) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode found = notice(deliveryNoteNo);
            if (found != null) {
                return found;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("the companion never imported " + deliveryNoteNo
                + "; notices: " + notices());
    }

    private static JsonNode notice(String deliveryNoteNo) throws Exception {
        for (JsonNode row : notices()) {
            if (deliveryNoteNo.equals(row.get("delivery_note_no").asText())) {
                return row;
            }
        }
        return null;
    }

    private static JsonNode notices() throws Exception {
        // Under a host a token names the application it may use (tql.app.use.<name>,
        // docs/codec-discovery.md decision 7) beside the role the policy reads.
        HttpResponse<String> response = send("supplier-edi", "/supplier-edi/api/notices", null,
                token("kita", List.of("SUPPLIER"), List.of("tql.app.use.supplier-edi")));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return MAPPER.readTree(response.body()).get("data");
    }

    private static void startSftpServer() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("localhost");
        sshd.setPort(0);
        SimpleGeneratorHostKeyProvider hostKeys = new SimpleGeneratorHostKeyProvider();
        sshd.setKeyPairProvider(hostKeys);
        PasswordAuthenticator auth = (username, password, session) -> "edi".equals(username)
                && "edi-secret".equals(password);
        sshd.setPasswordAuthenticator(auth);
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot.toAbsolutePath()));
        sshd.start();
        KeyPair hostKey = hostKeys.loadKeys(null).iterator().next();
        pinned = "[localhost]:" + sshd.getPort() + " "
                + PublicKeyEntry.toString(hostKey.getPublic()) + "\n";
    }

    private static void installApp(String appName, String example, String schema)
            throws IOException {
        Path appHome = installRoot.resolve(appName).resolve("1.0.0");
        Path source = Paths.get("..", "examples", example).toAbsolutePath().normalize();
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
                try {
                    Path destination = appHome.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(path, destination);
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        if ("procurement-app".equals(example)) {
            ProcurementAppCopy.prepare(appHome);
        }
        Files.writeString(appHome.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s&currentSchema=%s
                    username: %s
                    password: %s
                """.formatted(POSTGRES.getJdbcUrl(), schema, POSTGRES.getUsername(),
                POSTGRES.getPassword()));
        // The examples name the demo drop's port and the tour's poll cadence; the in-process
        // server has its own port, and a test waits for no thirty seconds.
        Path job = "procurement-app".equals(example)
                ? appHome.resolve("batch/edi/receipt-notice/job.yml")
                : appHome.resolve("batch/edi/receipt-notices/job.yml");
        String yaml = Files.readString(job);
        if (!yaml.contains("port: 2222")) {
            throw new IllegalStateException("The example's drop port moved; update this test");
        }
        yaml = yaml.replace("port: 2222", "port: " + sshd.getPort())
                .replace("delay: 30s", "delay: 500ms");
        Files.writeString(job, yaml);
        Files.writeString(appHome.resolve("security/known_hosts"), pinned);
        // The host serves what the catalogue lists, at the address the entry declares.
        new io.tesseraql.operations.app.AppCatalog(installRoot).register(
                new io.tesseraql.operations.app.InstalledApp(appName, "1.0.0",
                        appName + "/1.0.0", List.of()));
    }

    private static HttpResponse<String> send(String appId, String path, String body,
            String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + host.port(appId) + path))
                .header("Authorization", "Bearer " + token);
        if (body != null) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.GET();
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String opsToken() throws Exception {
        return token("ops", List.of("BATCH_OPERATOR"), List.of("tql.ops.view.*", "tql.ops.run.*"));
    }

    private static String token(String sub, List<String> roles, List<String> permissions)
            throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", sub, "roles", roles, "permissions", permissions))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Connection connect(String schema) throws Exception {
        String url = schema == null
                ? POSTGRES.getJdbcUrl()
                : POSTGRES.getJdbcUrl() + "&currentSchema=" + schema;
        return DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String schema, String sql, String... args) throws Exception {
        try (Connection connection = connect(schema);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setString(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
