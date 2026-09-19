package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.files.FileCodecs;
import io.tesseraql.yaml.config.FileDefaults;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.model.ColumnSpec;
import io.tesseraql.yaml.model.ExportSpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The preview's PDF renders what the route renders (docs/audit-low-leads.md slice 19): its
 * columns format in the app's configured locale and zone past a literal declaration, as the
 * route's own chain reads them (XH-19) — the preview used to walk the literal rung alone and
 * fall to the JVM's — and a template reads the route's other declared sources under their own
 * names (XH-10), where the seam used to hand the codec {@code main} and nothing else.
 */
class StudioSupportTest {

    private static final FileCodecs CODECS = FileCodecs.of(new io.tesseraql.pdf.PdfFileCodec());

    @Test
    void thePreviewFormatsInTheConfiguredLocaleAndZone(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: preview
                  files:
                    locale: de-DE
                    timezone: Asia/Tokyo
                """);
        FileDefaults defaults = FileDefaults.of(ManifestLoader.configOnly(dir));
        ExportSpec export = new ExportSpec("pdf", null, null, null, null, List.of(
                new ColumnSpec("amount", "Amount", null, "number", "#,##0.00", null),
                new ColumnSpec("at", "When", null, "datetime", "yyyy-MM-dd HH:mm", null)),
                null, null, null, null, null, null, null, null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("amount", new java.math.BigDecimal("1234.50"));
        row.put("at", java.time.OffsetDateTime.parse("2026-09-15T00:30:00Z"));

        byte[] pdf = StudioSupport.renderExportPdf(export, dir, dir, List.of(row), Map.of(),
                defaults, CODECS);

        // The German grouping and the Tokyo wall clock: the route's answer, not en_US/UTC's.
        assertThat(text(pdf)).contains("1.234,50").contains("2026-09-15 09:30");
    }

    @Test
    void aLiteralDeclarationWinsAndASourceExpressionFallsToTheConfiguredDefault(
            @TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: preview
                  files:
                    locale: de-DE
                """);
        FileDefaults defaults = FileDefaults.of(ManifestLoader.configOnly(dir));
        List<ColumnSpec> columns = List.of(
                new ColumnSpec("amount", "Amount", null, "number", "#,##0.00", null));
        Map<String, Object> row = Map.of("amount", new java.math.BigDecimal("1234.50"));
        ExportSpec literal = new ExportSpec("pdf", null, null, null, null, columns, "en-US",
                null, null, null, null, null, null, null);
        // A request-sourced locale has no request in the preview: not a locale tag for the
        // codec (literal() first), the configured default instead.
        ExportSpec sourced = new ExportSpec("pdf", null, null, null, null, columns,
                "principal.claim.locale", null, null, null, null, null, null, null);

        assertThat(text(StudioSupport.renderExportPdf(literal, dir, dir, List.of(row), Map.of(),
                defaults, CODECS))).contains("1,234.50");
        assertThat(text(StudioSupport.renderExportPdf(sourced, dir, dir, List.of(row), Map.of(),
                defaults, CODECS))).contains("1.234,50");
    }

    @Test
    void aTemplateReadsTheOtherDeclaredSources(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("order.html"), """
                <html xmlns:th="http://www.thymeleaf.org"><body>
                <h1 th:text="${header.first.customer}">customer</h1>
                <p th:each="row : ${main.rows}" th:text="${row.item}">item</p>
                </body></html>
                """);
        ExportSpec export = new ExportSpec("pdf", null, "order.html", null, null, List.of(),
                null, null, null, null, null, null, null, null);
        List<Map<String, Object>> lines = List.of(Map.of("item", "widget"));
        Map<String, Object> header = Map.of("rows", List.of(Map.of("customer", "ACME")),
                "rowCount", 1, "first", Map.of("customer", "ACME"));

        byte[] pdf = StudioSupport.renderExportPdf(export, dir, dir, lines,
                Map.of("header", header), FileDefaults.none(), CODECS);
        assertThat(text(pdf)).contains("ACME").contains("widget");

        // The seam's old shape: main alone, and the documented template is refused as broken.
        assertThatThrownBy(() -> StudioSupport.renderExportPdf(export, dir, dir, lines, Map.of(),
                FileDefaults.none(), CODECS))
                .hasMessageContaining("header.first.customer");
    }

    private static String text(byte[] pdf) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
