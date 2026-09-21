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
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The procurement gallery app's printable documents, over HTTP on the real app
 * (docs/procurement-documents-and-edi.md decision 7): the supplier prints their own quotation
 * as a PDF whose text — read back through PDFBox — carries the Japanese title, the supplier's
 * name and the total. That read-back is the proof the embedded sample font holds the glyphs:
 * with the 422-glyph seed subset the title rendered as boxes and the extracted text had no
 * 見積書 in it. The English document comes from the same template under {@code ?lang=en}, and a
 * competitor's quote id is a row outside the caller's reach, not a document.
 *
 * <p>The codec is on this module's test classpath, so the copy needs no resolved
 * {@code work/modules}: a single runtime discovers codecs from its own class loader when the
 * declared module directory holds no jar (docs/codec-discovery.md decision 1).
 */
@Testcontainers
class ProcurementDocumentsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    /** The gallery app's dev default (config: {@code ${JWT_SECRET:...}}). */
    private static final String JWT_SECRET = "dev-only-secret-change-me-in-production";
    private static final String QUOTE = "Q-RFQ-2002-P-200";

    static TesseraqlRuntime runtime;
    static Path appHome;

    @BeforeAll
    static void start() throws Exception {
        appHome = copyGalleryApp();
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
    void theSupplierPrintsTheirOwnQuotationInJapanese() throws Exception {
        HttpResponse<byte[]> response = print(QUOTE, "", token("minami", "SUPPLIER", "P-200"));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("content-type").orElse(""))
                .contains("application/pdf");
        assertThat(response.headers().firstValue("content-disposition").orElse(""))
                .contains("quote-" + QUOTE + ".pdf");
        assertThat(response.body()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        String text = text(response.body());
        // The title, the buyer, the subject, the issuer, the two items and the total: every
        // glyph of it is the sample font's, or it is a box. The issuer's address carries an
        // "ffi": the ligature glyph the layout substitutes keeps its code point in the subset,
        // or the PDF holds no text for it and "office" reads back as "o ce".
        assertThat(text).contains("見積書", "技術部", "御中",
                "新入社員用ワークステーション一式 見積依頼", "ミナミオフィスサプライ株式会社",
                "quotes@minami-office.example.com", "開発用ワークステーション",
                "27インチモニター", "245,000", "490,000", "618,000", "Page 1 / 1");
    }

    @Test
    void theEnglishDocumentComesFromTheSameTemplate() throws Exception {
        HttpResponse<byte[]> response = print(QUOTE, "?lang=en",
                token("minami", "SUPPLIER", "P-200"));

        assertThat(response.statusCode()).isEqualTo(200);
        String text = text(response.body());
        assertThat(text).contains("Quotation", "Total (excl. tax)", "Unit price", "618,000",
                "ミナミオフィスサプライ株式会社");
        assertThat(text).doesNotContain("見積書");
    }

    @Test
    void aCompetitorsQuotationIsNotADocument() throws Exception {
        HttpResponse<byte[]> response = print(QUOTE, "", token("kita", "SUPPLIER", "P-100"));

        assertThat(response.statusCode())
                .as("a quote outside the caller's row reach; body: "
                        + new String(response.body(), StandardCharsets.UTF_8))
                .isEqualTo(404);
        assertThat(new String(response.body(), StandardCharsets.UTF_8))
                .contains("TQL-LD-2863");
    }

    @Test
    void theSameQuotationDownloadsAsByteIdenticalPdfs() throws Exception {
        String token = token("minami", "SUPPLIER", "P-200");
        assertThat(print(QUOTE, "", token).body()).isEqualTo(print(QUOTE, "", token).body());
    }

    private static HttpResponse<byte[]> print(String quoteId, String query, String token)
            throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + runtime.port()
                + "/api/supplier/quotes/" + quoteId + "/print" + query))
                .header("Authorization", "Bearer " + token)
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String token(String sub, String role, String partner) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(MAPPER.writeValueAsBytes(TestClaims.addressed(
                Map.of("sub", sub, "roles", List.of(role), "partner", partner))));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = enc.encodeToString(
                mac.doFinal((header + "." + payload).getBytes(StandardCharsets.US_ASCII)));
        return header + "." + payload + "." + signature;
    }

    private static Path copyGalleryApp() throws IOException {
        Path source = Path.of("../examples/procurement-app").toAbsolutePath().normalize();
        Path target = Files.createTempDirectory("tesseraql-procurement-documents-it");
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
