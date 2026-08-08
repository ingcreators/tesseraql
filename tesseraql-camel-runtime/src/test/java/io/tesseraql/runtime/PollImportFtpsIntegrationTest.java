package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.DefaultFtplet;
import org.apache.ftpserver.ftplet.FtpRequest;
import org.apache.ftpserver.ftplet.FtpSession;
import org.apache.ftpserver.ftplet.FtpletResult;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.ssl.SslConfigurationFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The {@code ftps} poll source end to end against an in-process Apache FtpServer (no Docker),
 * closing the gap that let its transport settings sit wrong for a year: the branch had no test at
 * all, and each setting is one URI option long.
 *
 * <p>What this pins, beyond "a file arrives":
 * <ul>
 *   <li><b>The data channel is protected.</b> The client must negotiate {@code PBSZ}/{@code PROT P}
 *       — without them TLS covers the control channel only and the file's bytes cross the network
 *       in cleartext. The server's command trace is the evidence.</li>
 *   <li><b>The transfer is binary.</b> {@code TYPE I} has to appear. The trace is what proves
 *       this, not the payload: a lenient server round-trips even multi-byte text intact in ASCII
 *       mode, so a value-only assertion would pass against the broken setting.</li>
 *   <li><b>The server is verified.</b> The certificate chain is validated against the declared
 *       trust store — the very keystore this server presents from — with hostname checking on.
 *       The transfer completing at all is the evidence.</li>
 *   <li><b>The connection direction works.</b> Passive mode is what a NAT'd or containerized
 *       deployment can actually use, so {@code PASV} has to appear.</li>
 * </ul>
 */
@Testcontainers
class PollImportFtpsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    /** Every command the client sent, in order — the protocol-level evidence. */
    static final List<String> COMMANDS = new CopyOnWriteArrayList<>();

    /** Multi-byte UTF-8, so the pipeline is exercised on more than 7-bit text. */
    private static final String ORDER_NO = "注文-B1";
    /** Only ever reachable by a client that skipped certificate validation. */
    private static final String UNTRUSTED_ORDER_NO = "注文-X9";

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path ftpRoot;
    static Path keystore;
    /** A second self-signed keystore the server never presents from. */
    static Path untrusted;
    static FtpServer ftpServer;
    static int ftpPort;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        ftpRoot = Files.createTempDirectory("tesseraql-ftps-root");
        Files.createDirectories(ftpRoot.resolve("inbound"));
        Files.createDirectories(ftpRoot.resolve("inbound-untrusted"));
        Files.write(ftpRoot.resolve("inbound-untrusted/orders.csv"),
                ("orderNo,qty\n" + UNTRUSTED_ORDER_NO + ",9\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Files.write(ftpRoot.resolve("inbound/orders.csv"),
                ("orderNo,qty\n" + ORDER_NO + ",7\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        keystore = generateKeystore();
        untrusted = generateKeystore();
        startFtpsServer();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (ftpServer != null) {
            ftpServer.stop();
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
        if (ftpRoot != null) {
            deleteRecursively(ftpRoot);
        }
        if (keystore != null) {
            deleteRecursively(keystore.getParent());
        }
    }

    @Test
    void theRemoteCsvIsPolledOverAProtectedDataChannelAndImportedVerbatim() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        Map<String, Integer> rows = new LinkedHashMap<>();
        // Both conditions, not just the rows: the import now completes inside the consumer's
        // exchange, so the row becomes visible a moment *before* Camel moves the polled file.
        // Waiting on the row alone used to imply the move had happened; it no longer does.
        while (System.currentTimeMillis() < deadline
                && (rows.isEmpty() || Files.exists(ftpRoot.resolve("inbound/orders.csv")))) {
            rows.clear();
            try (Connection connection = connect();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select order_no, qty from imported_orders")) {
                while (rs.next()) {
                    rows.put(rs.getString("order_no"), rs.getInt("qty"));
                }
            }
            if (rows.isEmpty() || Files.exists(ftpRoot.resolve("inbound/orders.csv"))) {
                Thread.sleep(400);
            }
        }

        // The pipeline ran end to end over FTPS.
        assertThat(rows).containsEntry(ORDER_NO, 7);

        // The protocol trace is the direct evidence for each transport setting.
        // PBSZ 0 + PROT P: the data connection was encrypted, not just the control connection
        // that carried the credentials.
        assertThat(COMMANDS).contains("PBSZ 0");
        assertThat(COMMANDS).contains("PROT P");
        // TYPE I: an image (binary) transfer, so nothing is line-ending-translated in flight.
        assertThat(COMMANDS).contains("TYPE I");
        // PASV: the client opens the data connection, rather than asking this server to dial back
        // into it — the only direction a NAT'd or containerized deployment can use.
        assertThat(COMMANDS).anyMatch(command -> command.startsWith("PASV"));

        // The consumer moved the processed file off the inbound directory.
        assertThat(Files.exists(ftpRoot.resolve("inbound/orders.csv"))).isFalse();
    }

    @Test
    void aServerPresentingAnUntrustedCertificateIsRefused() throws Exception {
        // Same server, same credentials, same directory layout - only the trust store differs.
        // If the client were not validating, this would ingest exactly like the trusted case,
        // which is what makes it the honest test of the control: a positive-only test passes
        // whether or not verification happens.
        Path home = prepareAppHome("inbound-untrusted", untrusted);
        TesseraqlRuntime rogue = TesseraqlRuntime.start(home, freePort());
        try {
            // Long enough for several poll cycles at the job's 500ms delay.
            Thread.sleep(java.time.Duration.ofSeconds(6).toMillis());

            try (Connection connection = connect();
                    java.sql.PreparedStatement statement = connection.prepareStatement(
                            "select count(*) from imported_orders where order_no = ?")) {
                statement.setString(1, UNTRUSTED_ORDER_NO);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    assertThat(rows.getInt(1))
                            .as("a file behind an unverified certificate must not be ingested")
                            .isZero();
                }
            }
            // The file is still sitting there: the poll never got far enough to move it.
            assertThat(Files.exists(ftpRoot.resolve("inbound-untrusted/orders.csv"))).isTrue();
        } finally {
            rogue.close();
            deleteRecursively(home);
        }
    }

    /**
     * A throwaway self-signed certificate, generated per run by the JDK's own keytool — the
     * repository never carries a key. The client validates against this same file, so the
     * handshake exercises real chain and hostname checking (the certificate carries
     * {@code SAN=dns:localhost}).
     */
    private static Path generateKeystore() throws Exception {
        Path dir = Files.createTempDirectory("tesseraql-ftps-keystore");
        Path file = dir.resolve("ftps.p12");
        Process keytool = new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "ftps", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "1", "-storetype", "PKCS12",
                "-keystore", file.toString(), "-storepass", "changeit",
                "-dname", "CN=localhost, OU=test, O=tesseraql, C=JP",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1")
                .redirectErrorStream(true)
                .start();
        String output = new String(keytool.getInputStream().readAllBytes());
        if (keytool.waitFor() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
        return file;
    }

    private static void startFtpsServer() throws Exception {
        SslConfigurationFactory ssl = new SslConfigurationFactory();
        ssl.setKeystoreFile(keystore.toFile());
        ssl.setKeystorePassword("changeit");
        ssl.setKeyPassword("changeit");

        ListenerFactory listener = new ListenerFactory();
        listener.setServerAddress("localhost");
        listener.setPort(freePort());
        // Explicit FTPS (AUTH TLS), the mode the ftps: source speaks.
        listener.setImplicitSsl(false);
        listener.setSslConfiguration(ssl.createSslConfiguration());

        DataConnectionConfigurationFactory data = new DataConnectionConfigurationFactory();
        data.setSslConfiguration(ssl.createSslConfiguration());
        data.setPassiveAddress("localhost");
        data.setPassiveExternalAddress("localhost");
        listener.setDataConnectionConfiguration(data.createDataConnectionConfiguration());

        FtpServerFactory factory = new FtpServerFactory();
        factory.addListener("default", listener.createListener());

        BaseUser user = new BaseUser();
        user.setName("svc");
        user.setPassword("s3cr3t");
        user.setHomeDirectory(ftpRoot.toAbsolutePath().toString());
        user.setAuthorities(List.of(new WritePermission()));
        factory.getUserManager().save(user);

        factory.getFtplets().put("trace", new DefaultFtplet() {
            @Override
            public FtpletResult beforeCommand(FtpSession session, FtpRequest request) {
                COMMANDS.add(request.getRequestLine());
                return FtpletResult.DEFAULT;
            }
        });

        ftpServer = factory.createServer();
        ftpServer.start();
        ftpPort = listener.getPort();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table imported_orders (order_no varchar(64) primary key, qty int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        return prepareAppHome("inbound", keystore);
    }

    private static Path prepareAppHome(String remoteDir, Path trustStore)
            throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-ftps-it");
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
                  connectors:
                    poll:
                      allowedHosts:
                        - localhost
                      trustStore:
                        file: %s
                        password: changeit
                      credentials:
                        partner-ftps:
                          username: svc
                          password: s3cr3t
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), trustStore.toAbsolutePath()));

        Path jobDir = target.resolve("batch/partner");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: ftps
                    host: localhost
                    port: %d
                    path: /%s
                    credential: partner-ftps
                    include: "*.csv"
                    delay: 500ms
                import:
                  format: csv
                  columns:
                    - orderNo
                    - { name: qty, type: number }
                  sql:
                    file: upsert-order.sql
                """.formatted(ftpPort, remoteDir));
        Files.writeString(jobDir.resolve("upsert-order.sql"),
                "insert into imported_orders (order_no, qty)"
                        + " values (/* orderNo */ 'x', /* qty */ 0)\n");
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
