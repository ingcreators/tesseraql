package io.tesseraql.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileWriteSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPdfEngineTest {

    private static final String FONT = "TesseraQLSampleGothic-Regular.ttf";

    private final PdfFileCodec codec = new PdfFileCodec();

    @TempDir
    Path appHome;

    @BeforeEach
    void usePdfBoxEngine() throws IOException {
        System.setProperty(PdfEngines.PROPERTY, "pdfbox");
        Files.createDirectories(appHome.resolve("fonts"));
        try (InputStream font = getClass().getResourceAsStream("/fonts/" + FONT)) {
            Files.copy(font, appHome.resolve("fonts").resolve(FONT));
        }
    }

    @AfterEach
    void restoreDefaultEngine() {
        System.clearProperty(PdfEngines.PROPERTY);
    }

    @Test
    void rendersTheGridWithCjkTextAndPageNumbers() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, spec(), PdfFileCodecTest.rows().iterator());

        assertThat(out.toByteArray()).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        String text = PdfFileCodecTest.extractText(out.toByteArray());
        assertThat(text).contains("氏名", "登録日", "佐藤花子", "田中太郎", "2026-04-01");
        assertThat(text).contains("Page 1 / 1");
    }

    @Test
    void paginatesAndRepeatsTheTableHeader() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("user_name", "利用者" + i);
            row.put("joined_on", "2026-04-01");
            rows.add(row);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, spec(), rows.iterator());

        try (PDDocument document = Loader.loadPDF(out.toByteArray())) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            String secondPage = stripper.getText(document);
            assertThat(secondPage).contains("氏名");
            assertThat(secondPage).contains("Page 2 / " + document.getNumberOfPages());
        }
    }

    @Test
    void repeatsHeaderAndFooterBandsOnEveryPage() throws Exception {
        Path template = appHome.resolve("print.html");
        Files.writeString(template, """
                <html>
                <body>
                  <header>利用者一覧 帳票</header>
                  <footer>株式会社テッセラ</footer>
                  <table>
                    <tr><th>氏名</th></tr>
                    <tr th:each="row : ${rows}"><td th:text="${row.user_name}">x</td></tr>
                  </table>
                </body>
                </html>
                """);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            rows.add(Map.of("user_name", "利用者" + i));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template, null, appHome, null, null),
                rows.iterator());

        try (PDDocument document = Loader.loadPDF(out.toByteArray())) {
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                assertThat(text).contains("利用者一覧 帳票");
                assertThat(text).contains("株式会社テッセラ");
            }
        }
    }

    @Test
    void outputIsByteIdenticalAcrossRenders() throws Exception {
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        codec.write(first, spec(), PdfFileCodecTest.rows().iterator());
        codec.write(second, spec(), PdfFileCodecTest.rows().iterator());

        assertThat(first.toByteArray()).isEqualTo(second.toByteArray());
    }

    @Test
    void withoutAppFontsHelveticaCarriesAsciiAndReplacesTheRest() throws Exception {
        Files.delete(appHome.resolve("fonts").resolve(FONT));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("user_name", "name", null, null, null)),
                null, null, null, appHome, null, null),
                List.<Map<String, Object>>of(Map.of("user_name", "sato 佐藤")).iterator());

        String text = PdfFileCodecTest.extractText(out.toByteArray());
        assertThat(text).contains("sato ??");
    }

    private FileWriteSpec spec() {
        return new FileWriteSpec(List.of(
                new ColumnMapping("user_name", "氏名", null, null, null),
                new ColumnMapping("joined_on", "登録日", null, null, null)),
                null, null, null, appHome, null, null);
    }
}
