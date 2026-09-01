package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * The reference lookup field on the REAL purchase-request gallery app
 * (docs/reference-lookup.md slice 3): the supplier reference is a {@code domains/} document
 * carrying the whole {@code lookup:} block, the new-request form renders it, the synthesized
 * companions resolve and search the supplier master under its own route, and a submitted id
 * the master does not carry is refused inside the command's transaction.
 */
@Testcontainers
class PurchaseRequestLookupIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    /** The gallery app's dev default (config: {@code ${JWT_SECRET:...}}). */
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = copyGalleryApp();
        // The runtime applies the app's own db/migration at boot — V2 seeds the suppliers.
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
    void theNewRequestFormRendersTheSupplierLookup() throws Exception {
        HttpResponse<String> page = get("/requests/new");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("data-hc-lookup")
                .contains("name=\"supplier_code\"")
                .contains("name=\"supplier_id\"")
                .contains("hx-get=\"/requests/new/_lookup/supplier_id\"")
                .contains("Preferred supplier");
    }

    @Test
    void aSupplierCodeResolvesAgainstTheMaster() throws Exception {
        HttpResponse<String> field = get(
                "/requests/new/_lookup/supplier_id?supplier_code=S-100");

        assertThat(field.statusCode()).isEqualTo(200);
        assertThat(field.body()).contains("Northwind Office Supply")
                .contains("value=\"sup-100\"");
    }

    @Test
    void theDialogSearchesTheSupplierMaster() throws Exception {
        HttpResponse<String> dialog = get("/requests/new/_lookup/supplier_id/dialog");

        assertThat(dialog.statusCode()).isEqualTo(200);
        assertThat(dialog.body()).contains("<dialog class=\"hc-dialog\"")
                .contains("S-200 — Aurora Desks and Seating");

        HttpResponse<String> results = get(
                "/requests/new/_lookup/supplier_id/results?q=Cascade");
        assertThat(results.body()).contains("Cascade AV Equipment")
                .doesNotContain("Northwind");
    }

    @Test
    void aSubmitWithAnUnknownSupplierIsRefused() throws Exception {
        HttpResponse<String> refused = postJson("/requests/new",
                "{\"title\":\"Bogus supplier probe\",\"amount\":10,"
                        + "\"supplier_id\":\"ghost\"}");

        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("supplier_id").contains("invalid-reference");
    }

    @Test
    void aSubmitWithARealSupplierLandsInTheColumn() throws Exception {
        HttpResponse<String> created = postJson("/requests/new",
                "{\"title\":\"Monitor arms for the annex\",\"amount\":420,"
                        + "\"supplier_id\":\"sup-200\"}");

        assertThat(created.statusCode()).isEqualTo(201);
        try (Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("select supplier_id from"
                        + " purchase_requests where title = 'Monitor arms for the annex'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("sup-200");
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postJson(String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + runtime.port() + path))
                        .header("Authorization", "Bearer " + token())
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String token() throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", "requester-1", "roles", List.of("PR_READ", "PR_WRITE")))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path copyGalleryApp() throws IOException {
        Path source = Path.of("../examples/purchase-request-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-pr-lookup-it");
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
        return target;
    }
}
