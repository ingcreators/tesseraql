package io.tesseraql.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.CellRef;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileWriteSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The workbook's own limits, refused by the codec with a code that names what it could not
 * write (docs/export-hygiene.md P4). A cell holds at most 32,767 characters, a worksheet at most
 * 1,048,576 rows and 16,384 columns. Before this, the grid wrote a longer text (a file Excel
 * repairs on open), placement failed with POI's raw text naming no column, a jxls report
 * COMPLETED with the cell silently blank, and the grid's row and column limits surfaced as a
 * message-less {@code IllegalArgumentException} — a NULL reason on the asynchronous arm. A
 * template that is present but unusable (empty, a directory, not a workbook) fell through to the
 * grid and failed with the OUTPUT name as source, or with {@code TQL-LD-2856} naming the rows.
 *
 * <p>Fixtures are POI-authored: a template written by openpyxl holds {@code inlineStr} cells jxls
 * cannot copy, and a control would be red for the wrong reason.
 */
class JxlsFileCodecLimitsTest {

    private static final int CELL_LIMIT = 32_767;

    @TempDir
    Path dir;

    private final JxlsFileCodec codec = new JxlsFileCodec();

    private static Map<String, Object> row(String name, Object body) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("body", body);
        return row;
    }

    private static FileWriteSpec grid() {
        return new FileWriteSpec(List.of(ColumnMapping.of("name"), ColumnMapping.of("body")), null,
                null, null);
    }

    // ----------------------------------------------------------------- the 32,767-character cell

    @Test
    void theGridRefusesATextPastTheCellLimitNamingColumnAndRow() {
        List<Map<String, Object>> rows = List.of(row("short", "x".repeat(CELL_LIMIT)),
                row("long", "y".repeat(CELL_LIMIT + 1)));
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), grid(),
                ExportModel.streaming(rows.iterator(), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2836")
                .hasMessageContaining("'body'")
                .hasMessageContaining("row 2")
                .hasMessageContaining("32,767");
    }

    @Test
    void theGridWritesATextAtTheCellLimit() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, grid(), ExportModel.streaming(
                List.of(row("edge", "x".repeat(CELL_LIMIT))).iterator(), Map.of()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
                    .hasSize(CELL_LIMIT);
        }
    }

    @Test
    void placementRefusesATextPastTheCellLimitNamingColumnAndRow() throws Exception {
        Path template = placementTemplate();
        FileWriteSpec spec = new FileWriteSpec(List.of(
                new ColumnMapping("name", null, ColumnMapping.parseColumn("A")),
                new ColumnMapping("body", null, ColumnMapping.parseColumn("B"))),
                null, template, CellRef.parse("A2"));
        List<Map<String, Object>> rows = List.of(row("short", "ok"),
                row("long", "y".repeat(CELL_LIMIT + 1)));
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), spec,
                ExportModel.repeatable(rows, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2836")
                .hasMessageContaining("'body'")
                .hasMessageContaining("row 2");
    }

    @Test
    void aReportRefusesATextPastTheCellLimitNamingTheCell() throws Exception {
        Path template = reportTemplate();
        FileWriteSpec spec = new FileWriteSpec(List.of(), null, template, null);
        List<Map<String, Object>> control = List.of(row("short", "ten chars!"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, spec, ExportModel.repeatable(control, Map.of()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            // The control proves the template renders its cells: a fixture jxls cannot copy would
            // be red for the wrong reason.
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue())
                    .isEqualTo("ten chars!");
        }

        List<Map<String, Object>> rows = List.of(row("long", "y".repeat(CELL_LIMIT + 1)));
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), spec,
                ExportModel.repeatable(rows, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2836")
                .hasMessageContaining("B2");
    }

    // ------------------------------------------------------------ the worksheet's own dimensions

    @Test
    void theGridRefusesTheRowPastTheWorksheetLimitNamingTheLimit() {
        // 1,048,575 data rows fit under the header; the next is refused before fastexcel's
        // message-less IllegalArgumentException.
        Iterator<Map<String, Object>> endless = new Iterator<>() {
            private int produced;

            @Override
            public boolean hasNext() {
                return produced < 1_048_577;
            }

            @Override
            public Map<String, Object> next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                produced++;
                return row("r", "v");
            }
        };
        assertThatThrownBy(() -> codec.write(OutputStream.nullOutputStream(), grid(),
                ExportModel.streaming(endless, Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2836")
                .hasMessageContaining("1,048,576")
                .hasMessageContaining("splitBy");
    }

    @Test
    void theGridRefusesMoreColumnsThanAWorksheetHolds() {
        Map<String, Object> wide = new LinkedHashMap<>();
        for (int i = 0; i < 16_385; i++) {
            wide.put("c" + i, i);
        }
        assertThatThrownBy(() -> codec.write(OutputStream.nullOutputStream(),
                new FileWriteSpec(List.of(), null, null, null),
                ExportModel.streaming(List.of(wide).iterator(), Map.of())))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-LD-2836")
                .hasMessageContaining("16,384");
    }

    // ------------------------------------------------------------------- the zero-row header

    /** A zero-row grid carries its header row when the names are known (P5). */
    @Test
    void aZeroRowGridWritesItsHeaderWhenTheNamesAreKnown() throws Exception {
        ByteArrayOutputStream declared = new ByteArrayOutputStream();
        codec.write(declared, grid(), ExportModel.streaming(
                java.util.Collections.emptyIterator(), Map.of()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(declared.toByteArray()))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header).as("declared columns").isNotNull();
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("name");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("body");
        }

        ByteArrayOutputStream derived = new ByteArrayOutputStream();
        codec.write(derived, new FileWriteSpec(List.of(), null, null, null),
                ExportModel.streaming(new NamedEmpty(), Map.of()));
        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(derived.toByteArray()))) {
            Row header = workbook.getSheetAt(0).getRow(0);
            assertThat(header).as("names from the source").isNotNull();
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("id");
            assertThat(header.getCell(1).getStringCellValue()).isEqualTo("name");
        }
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

    // ------------------------------------------------------ a template that is present but unusable

    @Test
    void aTemplateThatIsNotAWorkbookIsRefusedByName() throws Exception {
        Path missing = dir.resolve("gone.xlsx");
        Path directory = Files.createDirectories(dir.resolve("folder.xlsx"));
        Path empty = Files.createFile(dir.resolve("empty.xlsx"));
        Path text = Files.writeString(dir.resolve("text.xlsx"), "not a workbook",
                StandardCharsets.UTF_8);
        for (Path template : List.of(missing, directory, empty, text)) {
            for (CellRef start : new CellRef[]{null, CellRef.parse("A2")}) {
                FileWriteSpec spec = new FileWriteSpec(
                        List.of(ColumnMapping.of("name"), ColumnMapping.of("body")), null,
                        template, start);
                assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), spec,
                        ExportModel.repeatable(List.of(row("a", "b")), Map.of())))
                        .as(template.getFileName() + (start == null ? " report" : " placement"))
                        .isInstanceOf(TqlException.class)
                        .hasMessageContaining("TQL-LD-2837")
                        .hasMessageContaining(template.getFileName().toString());
            }
        }
    }

    /**
     * A workbook outside the application home is refused before a byte is written, whatever
     * mode the spec declares (docs/audit-low-leads.md slice 14, XH-14): lint and boot fence
     * the declaration, and this is the codec's own twin of the pdf codec's rule, for a spec
     * no compiler fenced. The same workbook inside the home writes.
     */
    @Test
    void aTemplateOutsideTheApplicationHomeIsRefusedBeforeAByteIsWritten() throws Exception {
        Path home = Files.createDirectories(dir.resolve("app"));
        Path outside = placementTemplate();
        Path inside = Files.copy(outside, home.resolve("placement.xlsx"));
        for (CellRef start : new CellRef[]{null, CellRef.parse("A2")}) {
            FileWriteSpec escaped = new FileWriteSpec(
                    List.of(new ColumnMapping("name", null, ColumnMapping.parseColumn("A")),
                            new ColumnMapping("body", null, ColumnMapping.parseColumn("B"))),
                    null, outside, start, home, null, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThatThrownBy(() -> codec.write(out, escaped,
                    ExportModel.repeatable(List.of(row("a", "b")), Map.of())))
                    .as(start == null ? "report" : "placement")
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-LD-2837")
                    .hasMessageContaining("outside the application home")
                    .hasMessageContaining(outside.getFileName().toString());
            assertThat(out.size()).as("nothing written before the refusal").isZero();
        }
        FileWriteSpec confined = new FileWriteSpec(
                List.of(new ColumnMapping("name", null, ColumnMapping.parseColumn("A")),
                        new ColumnMapping("body", null, ColumnMapping.parseColumn("B"))),
                null, inside, CellRef.parse("A2"), home, null, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, confined, ExportModel.repeatable(List.of(row("a", "b")), Map.of()));
        assertThat(out.size()).isPositive();
    }

    // ------------------------------------------------------------------------------ fixtures

    /** A placement template: a title in row 1, the data area from A2. */
    private Path placementTemplate() throws Exception {
        Path template = dir.resolve("placement.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("data");
            sheet.createRow(0).createCell(0).setCellValue("Report");
            sheet.createRow(1);
            try (OutputStream out = Files.newOutputStream(template)) {
                workbook.write(out);
            }
        }
        return template;
    }

    /** A jxls report template over {@code main.rows}: name in A, body in B. */
    private Path reportTemplate() throws Exception {
        Path template = dir.resolve("report.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("report");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Report");
            Row each = sheet.createRow(1);
            each.createCell(0).setCellValue("${r.name}");
            each.createCell(1).setCellValue("${r.body}");
            comment(sheet, title.getCell(0), "jx:area(lastCell=\"B2\")");
            comment(sheet, each.getCell(0),
                    "jx:each(items=\"main.rows\" var=\"r\" lastCell=\"B2\")");
            try (OutputStream out = Files.newOutputStream(template)) {
                workbook.write(out);
            }
        }
        return template;
    }

    private static void comment(Sheet sheet, org.apache.poi.ss.usermodel.Cell cell, String text) {
        org.apache.poi.ss.usermodel.Drawing<?> drawing = sheet.createDrawingPatriarch();
        org.apache.poi.ss.usermodel.CreationHelper factory = sheet.getWorkbook()
                .getCreationHelper();
        org.apache.poi.ss.usermodel.ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setCol2(cell.getColumnIndex() + 2);
        anchor.setRow1(cell.getRowIndex());
        anchor.setRow2(cell.getRowIndex() + 2);
        org.apache.poi.ss.usermodel.Comment comment = drawing.createCellComment(anchor);
        comment.setString(factory.createRichTextString(text));
        cell.setCellComment(comment);
    }
}
