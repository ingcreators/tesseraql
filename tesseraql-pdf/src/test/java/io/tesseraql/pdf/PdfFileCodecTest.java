package io.tesseraql.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileWriteSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfFileCodecTest {

    private static final String FONT = "TesseraQLSampleGothic-Regular.ttf";

    private final PdfFileCodec codec = new PdfFileCodec();

    @TempDir
    Path appHome;

    @BeforeEach
    void installSampleFont() throws IOException {
        Files.createDirectories(appHome.resolve("fonts"));
        try (InputStream font = getClass().getResourceAsStream("/fonts/" + FONT)) {
            Files.copy(font, appHome.resolve("fonts").resolve(FONT));
        }
    }

    /** A zero-row grid prints the names its source knew, as it already did declared ones (P5). */
    @Test
    void aZeroRowGridPrintsTheSourcesColumnNames() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, null, null, appHome, null, null),
                io.tesseraql.core.files.ExportModel.repeatable(
                        io.tesseraql.core.files.SpooledRows.drain(
                                new io.tesseraql.core.spool.FileTempStore(appHome.resolve("spool")),
                                new NamedEmpty()),
                        java.util.Map.of()));
        assertThat(extractText(out.toByteArray())).contains("id").contains("name");
    }

    /** A row source that knows its column names before any row, as a JDBC cursor does. */
    private static final class NamedEmpty
            implements
                Iterator<Map<String, Object>>,
                io.tesseraql.core.files.NamedRows {
        @Override
        public List<String> columns() {
            return List.of("id", "name");
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Map<String, Object> next() {
            throw new java.util.NoSuchElementException();
        }
    }

    @Test
    void gridExportEmbedsCjkTextAndPageNumbers() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("user_name", "氏名", null, null, null),
                new ColumnMapping("joined_on", "登録日", null, "date", null)),
                null, null, null, appHome, null, "Asia/Tokyo"),
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of()));

        assertThat(out.toByteArray()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        String text = extractText(out.toByteArray());
        assertThat(text).contains("氏名", "登録日", "佐藤花子", "田中太郎", "2026-04-01");
        assertThat(text).contains("Page 1 / 1");
    }

    @Test
    void outputIsByteIdenticalAcrossRenders() throws Exception {
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        FileWriteSpec spec = new FileWriteSpec(List.of(), null, null, null, appHome, null, null);
        codec.write(first, spec,
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of()));
        codec.write(second, spec,
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of()));

        assertThat(first.toByteArray()).isEqualTo(second.toByteArray());
    }

    @Test
    void normalizedMetadataCarriesNoTimestamps() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, null, null, appHome, null, null),
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of()));

        try (PDDocument document = Loader.loadPDF(out.toByteArray())) {
            assertThat(document.getDocumentInformation().getProducer()).isEqualTo("TesseraQL");
            assertThat(document.getDocumentInformation().getCreationDate()).isNull();
            assertThat(document.getDocumentInformation().getModificationDate()).isNull();
            assertThat(document.getDocumentCatalog().getMetadata()).isNull();
        }
    }

    @Test
    void templateModeRendersThePrintTemplate() throws Exception {
        Path routeDir = Files.createDirectories(appHome.resolve("web/users/print"));
        Files.writeString(routeDir.resolve("print.html"), """
                <html xmlns:th="http://www.thymeleaf.org">
                <head>
                  <title>利用者一覧</title>
                  <style>
                    @page { size: A4; margin: 18mm;
                      @bottom-center { content: counter(page) " / " counter(pages); } }
                    body { font-family: 'TesseraQL Sample Gothic'; font-size: 10pt; }
                  </style>
                </head>
                <body>
                  <h1>利用者一覧</h1>
                  <table>
                    <tr th:each="row : ${main.rows}">
                      <td th:text="${row.user_name}">name</td>
                      <td th:text="${row.joined_on}">joined</td>
                    </tr>
                  </table>
                </body>
                </html>
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("user_name", null, null, null, null),
                new ColumnMapping("joined_on", null, null, "date", null)),
                null, routeDir.resolve("print.html"), null, appHome, null, "Asia/Tokyo"),
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of()));

        String text = extractText(out.toByteArray());
        assertThat(text).contains("利用者一覧", "佐藤花子", "田中太郎", "1 / 1");
    }

    @Test
    void gridExportRendersASqlTimeAsWallClockText() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "late");
        row.put("starts_at", java.sql.Time.valueOf("22:30:00"));
        row.put("ends_at", java.sql.Time.valueOf("23:45:00"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                ColumnMapping.of("label"),
                ColumnMapping.of("starts_at"),
                new ColumnMapping("ends_at", null, null, null, "HH:mm")),
                null, null, null, appHome, null, "Asia/Tokyo"),
                io.tesseraql.core.files.ExportModel.repeatable(List.of(row), Map.of()));

        String text = extractText(out.toByteArray());
        assertThat(text).contains("late", "22:30:00", "23:45");
        assertThat(text).doesNotContain("23:45:00");
    }

    // ------------------------------------------------ the template renders in the export's locale

    /**
     * A print template renders in the export's locale (docs/export-hygiene.md P6): its
     * utilities, {@code ${#locale}} and its message expressions follow {@code locale:}. The
     * context used to be {@code Locale.ROOT} whatever the export declared — two locales in one
     * document — and ANY message lookup threw under ROOT after the query had run.
     */
    @Test
    void aTemplateRendersInTheExportsLocale() throws Exception {
        Path template = localizedTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template, null, appHome, "de-DE",
                "UTC"), io.tesseraql.core.files.ExportModel.repeatable(rows(), sample()));

        String text = extractText(out.toByteArray());
        assertThat(text).contains("1.234,50").contains("Donnerstag").contains("deutsch")
                .contains("de-DE");
    }

    /** An export that declares no locale renders in English, whatever the JVM's default is. */
    @Test
    void anUndeclaredLocaleRendersInEnglishWhateverTheJvmSays() throws Exception {
        Path template = localizedTemplate();
        java.util.Locale jvm = java.util.Locale.getDefault();
        String text;
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            codec.write(out, new FileWriteSpec(List.of(), null, template, null, appHome, null,
                    "UTC"), io.tesseraql.core.files.ExportModel.repeatable(rows(), sample()));
            text = extractText(out.toByteArray());
        } finally {
            java.util.Locale.setDefault(jvm);
        }
        assertThat(text).contains("1,234.50").contains("Thursday").contains("english")
                .doesNotContain("deutsch");
    }

    /** A tag with no language (a folding {@code request.locale}) renders in English, never ROOT. */
    @Test
    void anEmptyLanguageTagRendersInEnglish() throws Exception {
        Path template = localizedTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template, null, appHome, "und",
                "UTC"), io.tesseraql.core.files.ExportModel.repeatable(rows(), sample()));
        assertThat(extractText(out.toByteArray())).contains("english").contains("1,234.50");
    }

    /** A template that cannot be rendered is the codec's own failure, naming the template. */
    @Test
    void aTemplateErrorIsTheCodecsOwnFailureNamingTheTemplate() throws Exception {
        Path template = appHome.resolve("broken.html");
        Files.writeString(template, "<html><body><p th:text=\"${#numbers.formatDecimal(}\">x</p>"
                + "</body></html>");
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(),
                new FileWriteSpec(List.of(), null, template, null, appHome, null, null),
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2831")
                .hasMessageContaining("broken.html");
    }

    /** The template's other values: a fixed Thursday for the date utility. */
    private static Map<String, Object> sample() {
        return Map.of("d", java.util.Date.from(java.time.Instant.parse("2026-03-05T12:00:00Z")));
    }

    /**
     * A template that leans on the locale three ways: a number utility with the locale's
     * separators, a date utility with the locale's day and month names, and a message expression
     * with a German and an English sibling.
     */
    private Path localizedTemplate() throws IOException {
        Path template = appHome.resolve("greet.html");
        Files.writeString(template, """
                <html xmlns:th="http://www.thymeleaf.org"><body>
                <p th:text="${#numbers.formatDecimal(1234.5, 1, 'DEFAULT', 2, 'DEFAULT')}">n</p>
                <p th:text="${#dates.format(d, 'EEEE d MMMM yyyy')}">d</p>
                <p th:text="${#locale.toLanguageTag()}">l</p>
                <p th:text="#{greeting}">g</p>
                </body></html>
                """);
        Files.writeString(appHome.resolve("greet_de.properties"), "greeting=deutsch\n");
        Files.writeString(appHome.resolve("greet_en.properties"), "greeting=english\n");
        return template;
    }

    @Test
    void aTemplateOutsideTheResourceRootIsRejected(@TempDir Path elsewhere) throws Exception {
        Path template = elsewhere.resolve("evil.html");
        Files.writeString(template, "<html><body>x</body></html>");

        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(),
                new FileWriteSpec(List.of(), null, template, null, appHome, null, null),
                io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of())))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(((TqlException) ex).code().toString())
                        .isEqualTo("TQL-LD-2832"));
    }

    @Test
    void importIsRejected() {
        assertThatThrownBy(() -> codec.read(new ByteArrayInputStream(new byte[0]),
                new io.tesseraql.core.files.FileReadSpec(List.of(), true, null, 1),
                (rowNumber, values) -> {
                }))
                .isInstanceOf(TqlException.class)
                .satisfies(ex -> assertThat(((TqlException) ex).code().toString())
                        .isEqualTo("TQL-LD-2830"));
    }

    @Test
    void anUnknownEngineFailsLoudly() {
        System.setProperty(PdfEngines.PROPERTY, "missing");
        try {
            assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(),
                    new FileWriteSpec(List.of(), null, null, null, appHome, null, null),
                    io.tesseraql.core.files.ExportModel.repeatable(rows(), java.util.Map.of())))
                    .isInstanceOf(TqlException.class)
                    .satisfies(ex -> assertThat(((TqlException) ex).code().toString())
                            .isEqualTo("TQL-LD-2833"));
        } finally {
            System.clearProperty(PdfEngines.PROPERTY);
        }
    }

    static List<Map<String, Object>> rows() {
        Map<String, Object> sato = new LinkedHashMap<>();
        sato.put("user_name", "佐藤花子");
        sato.put("joined_on", LocalDate.of(2026, 4, 1));
        sato.put("logins", new BigDecimal("12"));
        Map<String, Object> tanaka = new LinkedHashMap<>();
        tanaka.put("user_name", "田中太郎");
        tanaka.put("joined_on", LocalDate.of(2026, 5, 15));
        tanaka.put("logins", new BigDecimal("3"));
        return List.of(sato, tanaka);
    }

    static String extractText(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
