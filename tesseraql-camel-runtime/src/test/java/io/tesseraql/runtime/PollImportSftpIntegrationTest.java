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
import java.util.stream.Stream;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Phase 26 end-to-end over SFTP: a {@code poll:}-triggered file-import job polls a remote
 * (allow-listed) SFTP directory — served here by an in-process Apache MINA sshd server, no Docker
 * — and ingests a CSV it finds through the file-import pipeline. FTPS uses the same recipe with
 * {@code source: ftps}; only the Camel endpoint scheme differs.
 */
@Testcontainers
class PollImportSftpIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path sftpRoot;
    static SshServer sshd;

    @BeforeAll
    static void start() throws Exception {
        seedDatabase();
        sftpRoot = Files.createTempDirectory("tesseraql-sftp-root");
        Files.createDirectories(sftpRoot.resolve("inbound"));
        Files.writeString(sftpRoot.resolve("inbound/orders.csv"), "orderNo,qty\nB-1,7\nB-2,9\n");
        startSftpServer();
        appHome = prepareAppHome();
        runtime = TesseraqlRuntime.start(appHome, freePort());
    }

    @AfterAll
    static void stop() throws IOException {
        if (runtime != null) {
            runtime.close();
        }
        if (sshd != null) {
            sshd.stop(true);
        }
        if (appHome != null) {
            deleteRecursively(appHome);
        }
        if (sftpRoot != null) {
            deleteRecursively(sftpRoot);
        }
    }

    @Test
    void theRemoteCsvIsPolledAndImported() throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(45).toMillis();
        Map<String, Integer> rows = new LinkedHashMap<>();
        while (System.currentTimeMillis() < deadline && rows.size() < 2) {
            rows.clear();
            try (Connection connection = connect();
                    Statement statement = connection.createStatement();
                    ResultSet rs = statement.executeQuery(
                            "select order_no, qty from imported_orders order by order_no")) {
                while (rs.next()) {
                    rows.put(rs.getString("order_no"), rs.getInt("qty"));
                }
            }
            if (rows.size() < 2) {
                Thread.sleep(400);
            }
        }

        assertThat(rows).containsEntry("B-1", 7).containsEntry("B-2", 9);
        // The consumer moved the processed file off the inbound directory.
        assertThat(Files.exists(sftpRoot.resolve("inbound/orders.csv"))).isFalse();
    }

    private static void startSftpServer() throws IOException {
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("localhost");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        PasswordAuthenticator auth = (username, password, session) -> "svc".equals(username)
                && "s3cr3t".equals(password);
        sshd.setPasswordAuthenticator(auth);
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        // Root the SFTP user at sftpRoot, so the remote /inbound is sftpRoot/inbound.
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot.toAbsolutePath()));
        sshd.start();
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void seedDatabase() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "create table imported_orders (order_no varchar(32) primary key, qty int)");
        }
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-sftp-it");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> copy(source, target, path));
        }
        // db config plus the poll connector policy (allow-list + credential) the job draws on; the
        // example app declares no connectors block, so this deep-merges cleanly.
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
                      credentials:
                        partner-sftp:
                          username: svc
                          password: s3cr3t
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()));

        Path jobDir = target.resolve("batch/partner");
        Files.createDirectories(jobDir);
        Files.writeString(jobDir.resolve("job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    source: sftp
                    host: localhost
                    port: %d
                    path: /inbound
                    credential: partner-sftp
                    include: "*.csv"
                    delay: 500ms
                import:
                  format: csv
                  columns:
                    - orderNo
                    - { name: qty, type: number }
                  sql:
                    file: upsert-order.sql
                """.formatted(sshd.getPort()));
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
