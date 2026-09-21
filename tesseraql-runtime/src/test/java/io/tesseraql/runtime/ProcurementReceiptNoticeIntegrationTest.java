package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.security.KeyPair;
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
 * The procurement demo's receipt-notice feed end to end (docs/procurement-documents-and-edi.md
 * decisions 3, 4 and 7): the job's export step writes the delivery notes received on the
 * business date and its push step delivers the file whole to an SFTP drop — an in-process
 * Apache MINA sshd here, no Docker — under a <em>pinned</em> host key: the copy's
 * {@code security/known_hosts} holds the server's generated key, so this is the first SFTP test
 * in the module that runs with strict host-key checking on. The negative twin is the committed
 * state of the example: the same file with no key in it, and the job fails with
 * {@code TQL-BATCH-5315} without delivering — refused, never trusted on first use.
 */
@Testcontainers
class ProcurementReceiptNoticeIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The gallery app's dev default (config: {@code ${JWT_SECRET:...}}). */
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";
    private static final String ORDER = "ORD-S3-EDI";
    /** The example's committed file: a comment, no key. */
    private static final String KEY_LESS = """
            # The SSH host keys the receipt-notice feed may deliver to. Nothing is pinned here.
            """;

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path sftpRoot;
    static SshServer sshd;
    static String pinned;

    @BeforeAll
    static void start() throws Exception {
        sftpRoot = Files.createTempDirectory("tesseraql-receipt-notice-drop");
        Files.createDirectories(sftpRoot.resolve("drop"));
        startSftpServer();
        appHome = copyGalleryApp();
        runtime = TesseraqlRuntime.start(appHome, 0);
        // A received shipment over the seeded lowest quote, as the tour leaves one.
        execute("insert into orders (id, rfq_id, quote_id, partner_id, total_amount, is_lowest,"
                + " delta_pct, ordered_by) values (?, 'RFQ-2002', 'Q-RFQ-2002-P-200', 'P-200',"
                + " 618000.00, true, 0, 'hara')", ORDER);
        execute("insert into shipments (order_id, ship_date, carrier, delivery_note_no,"
                + " shipped_by, received_at, received_by) values (?, date '2026-09-25',"
                + " 'ヤマト運輸', 'DN-2026-001', 'minami', timestamp '2026-09-21 10:30:00',"
                + " 'sato')", ORDER);
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (sshd != null) {
            sshd.stop(true);
        }
        deleteRecursively(appHome);
        deleteRecursively(sftpRoot);
    }

    @Test
    void theNoticeLandsWholeOnThePinnedDrop() throws Exception {
        HttpResponse<String> run = send("POST",
                "/_tesseraql/ops/batch/jobs/edi.receiptNotice/run",
                "{\"businessDate\": \"2026-09-21\"}");
        assertThat(run.body()).as(run.body()).contains("COMPLETED");

        Path delivered = sftpRoot.resolve("drop/receipt-notice-2026-09-21.csv");
        assertThat(delivered).exists();
        byte[] bytes = Files.readAllBytes(delivered);
        // A program's file: no byte-order mark, the header line, the row as the export's
        // columns render it.
        assertThat(bytes[0]).isEqualTo((byte) 'd');
        String csv = new String(bytes, StandardCharsets.UTF_8);
        assertThat(csv).startsWith(
                "delivery_note_no,order_id,partner_id,partner_name,ship_date,carrier,received_at");
        assertThat(csv).contains("DN-2026-001," + ORDER + ",P-200,ミナミオフィスサプライ株式会社,"
                + "2026-09-25,ヤマト運輸,2026-09-21 10:30:00");
        // Staged under a dot-name and renamed on completion: nothing partial is left behind.
        try (Stream<Path> files = Files.list(sftpRoot.resolve("drop"))) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith(".")))
                    .isEmpty();
        }
    }

    @Test
    void theCommittedKeyLessFileRefusesTheServer() throws Exception {
        Path knownHosts = appHome.resolve("security/known_hosts");
        Files.writeString(knownHosts, KEY_LESS);
        try {
            HttpResponse<String> run = send("POST",
                    "/_tesseraql/ops/batch/jobs/edi.receiptNotice/run",
                    "{\"businessDate\": \"2026-09-22\"}");
            assertThat(run.body()).as(run.body()).contains("FAILED");
            String executionId = MAPPER.readTree(run.body()).path("executionId").asText();
            HttpResponse<String> detail = send("GET",
                    "/_tesseraql/ops/batch/executions/" + executionId, null);
            assertThat(detail.body()).contains("TQL-BATCH-5315");
            assertThat(sftpRoot.resolve("drop/receipt-notice-2026-09-22.csv")).doesNotExist();
        } finally {
            Files.writeString(knownHosts, pinned);
        }
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
        // The pin, as ssh-keyscan would write it for a server on a port other than 22.
        KeyPair hostKey = hostKeys.loadKeys(null).iterator().next();
        pinned = "[localhost]:" + sshd.getPort() + " "
                + PublicKeyEntry.toString(hostKey.getPublic()) + "\n";
    }

    private static Path copyGalleryApp() throws IOException {
        Path source = Path.of("../examples/procurement-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-receipt-notice-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
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
            });
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
        // The example names the demo drop's port; the in-process server has its own.
        Path job = target.resolve("batch/edi/receipt-notice/job.yml");
        String yaml = Files.readString(job);
        if (!yaml.contains("port: 2222")) {
            throw new IllegalStateException("The example's drop port moved; update this test");
        }
        Files.writeString(job, yaml.replace("port: 2222", "port: " + sshd.getPort()));
        Files.writeString(target.resolve("security/known_hosts"), pinned);
        return target;
    }

    private static HttpResponse<String> send(String method, String path, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + runtime.port() + path))
                .header("Authorization", "Bearer " + token());
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
        } else {
            request.GET();
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "ops", "roles", List.of("BATCH_OPERATOR"),
                        "permissions", List.of("tql.ops.view.*", "tql.ops.run.*")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static void execute(String sql, String... args) throws Exception {
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.PreparedStatement statement = connection.prepareStatement(sql)) {
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
