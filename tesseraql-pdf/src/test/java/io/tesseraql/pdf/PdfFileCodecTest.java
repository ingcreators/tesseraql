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

    @Test
    void gridExportEmbedsCjkTextAndPageNumbers() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("user_name", "氏名", null, null, null),
                new ColumnMapping("joined_on", "登録日", null, "date", null)),
                null, null, null, appHome, null, "Asia/Tokyo"), rows().iterator());

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
        codec.write(first, spec, rows().iterator());
        codec.write(second, spec, rows().iterator());

        assertThat(first.toByteArray()).isEqualTo(second.toByteArray());
    }

    @Test
    void normalizedMetadataCarriesNoTimestamps() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, null, null, appHome, null, null),
                rows().iterator());

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
                    <tr th:each="row : ${rows}">
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
                rows().iterator());

        String text = extractText(out.toByteArray());
        assertThat(text).contains("利用者一覧", "佐藤花子", "田中太郎", "1 / 1");
    }

    @Test
    void aTemplateOutsideTheResourceRootIsRejected(@TempDir Path elsewhere) throws Exception {
        Path template = elsewhere.resolve("evil.html");
        Files.writeString(template, "<html><body>x</body></html>");

        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(),
                new FileWriteSpec(List.of(), null, template, null, appHome, null, null),
                rows().iterator()))
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
                    rows().iterator()))
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
