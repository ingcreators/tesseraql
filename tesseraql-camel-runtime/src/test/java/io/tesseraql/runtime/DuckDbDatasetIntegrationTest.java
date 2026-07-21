package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.attachment.AttachmentStore;
import io.tesseraql.operations.attachment.JdbcAttachmentStore;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration test for the dataset channel (docs/duckdb.md): a scan-passed managed attachment is
 * queryable as {@code ${dataset.*}} by its owner — spooled once into the fence's one bridge
 * directory — while the same reference is a neutral refusal for another caller, for a
 * still-pending upload, and the refusals never distinguish missing from denied.
 */
@Testcontainers
class DuckDbDatasetIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final String KEY_A = "key-user-a";
    private static final String KEY_B = "key-user-b";

    TesseraqlRuntime runtime;
    Path appHome;
    String cleanId;
    String pendingId;

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
    void resolvesAnOwnedCleanDatasetAndNeutrallyRefusesTheRest() throws Exception {
        appHome = prepareApp();

        runtime = TesseraqlRuntime.start(appHome, freePort());

        // The owner reads their scan-passed upload through the dataset placeholder.
        HttpResponse<String> owned = get("/api/report?id=" + cleanId, KEY_A);
        assertThat(owned.statusCode()).isEqualTo(200);
        assertThat(owned.body()).contains("\"n\":2");

        // Another authenticated caller, a pending upload, and a guessed id: one neutral answer.
        assertThat(get("/api/report?id=" + cleanId, KEY_B).statusCode()).isEqualTo(500);
        assertThat(get("/api/report?id=" + pendingId, KEY_A).statusCode()).isEqualTo(500);
        assertThat(get("/api/report?id=does-not-exist", KEY_A).statusCode()).isEqualTo(500);
    }

    private HttpResponse<String> get(String path, String apiKey) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port() + path))
                        .header("X-API-Key", apiKey)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private Path prepareApp() throws Exception {
        Path home = Files.createTempDirectory("tesseraql-duckdb-dataset");
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/application.yml"), """
                server:
                  port: 0

                db:
                  main:
                    url: %s
                    username: %s
                    password: %s

                tesseraql:
                  app:
                    name: duckdb-dataset-app
                  security:
                    policies:
                      data.read:
                        anyOf:
                          - role: READER
                    apiKeys:
                      header: X-API-Key
                      clients:
                        userA:
                          secretHash: %s
                          subject: user-a
                          roles: [READER]
                        userB:
                          secretHash: %s
                          subject: user-b
                          roles: [READER]
                  datasources:
                    main:
                      jdbcUrl: ${db.main.url}
                      username: ${db.main.username}
                      password: ${db.main.password}
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                """.formatted(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword(), sha256Hex(KEY_A), sha256Hex(KEY_B)));

        // An attachment document turns on the managed store (and with it, datasets).
        Files.createDirectories(home.resolve("attachments"));
        Files.writeString(home.resolve("attachments/uploads.yml"), """
                version: tesseraql/v1
                id: uploads
                kind: attachment
                basePath: /records/{recordId}/files
                record: { entity: record, key: recordId }
                security:
                  auth: apiKey
                  policy: data.read
                """);

        // Seed the same table and blob directory the runtime will open: a clean Parquet upload
        // owned by user-a, and a still-pending one.
        PGSimpleDataSource main = new PGSimpleDataSource();
        main.setUrl(POSTGRES.getJdbcUrl());
        main.setUser(POSTGRES.getUsername());
        main.setPassword(POSTGRES.getPassword());
        JdbcAttachmentStore store = new JdbcAttachmentStore(main);
        store.ensureSchema();
        Path blobs = home.resolve("work/blob/tesseraql");
        Files.createDirectories(blobs.resolve("datasets"));
        Path parquet = blobs.resolve("datasets/report.parquet");
        try (Connection duck = DriverManager.getConnection("jdbc:duckdb:");
                Statement statement = duck.createStatement()) {
            statement.execute(
                    """
                            COPY (
                              SELECT * FROM (VALUES ('widgets', 300), ('gadgets', 120)) AS t(category, total)
                            ) TO '%s' (FORMAT parquet)
                            """
                            .formatted(parquet));
        }
        cleanId = store.insert(new AttachmentStore.NewAttachment("record", "R-1",
                "report.parquet", "application/octet-stream", Files.size(parquet), "sha-report",
                "datasets/report.parquet", "clean", "user-a")).id();
        pendingId = store.insert(new AttachmentStore.NewAttachment("record", "R-1",
                "pending.parquet", "application/octet-stream", Files.size(parquet), "sha-pending",
                "datasets/report.parquet", "pending", "user-a")).id();

        Path route = home.resolve("web/api/report");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: report.read
                kind: route
                recipe: query-json
                datasource: analytics
                security:
                  auth: apiKey
                  policy: data.read
                input:
                  id: { type: string, required: true }
                sql:
                  file: report.sql
                  mode: query
                  params:
                    report: query.id
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(route.resolve("report.sql"), """
                select count(*) as n
                from read_parquet(/* ${dataset.report} */ 'dummy.parquet')
                """);
        return home;
    }

    private static String sha256Hex(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
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
