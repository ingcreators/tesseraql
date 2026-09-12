package io.tesseraql.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.files.CellRef;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JxlsFileCodecTest {

    @TempDir
    Path dir;

    /** The day fraction of 22:30 - what a real Excel time cell holds. */
    private static final double TWENTY_TWO_THIRTY = 0.9375;

    private final JxlsFileCodec codec = new JxlsFileCodec();

    private static io.tesseraql.core.files.ExportModel streaming(List<Map<String, Object>> rows) {
        return io.tesseraql.core.files.ExportModel.streaming(rows.iterator(), Map.of());
    }

    private static io.tesseraql.core.files.ExportModel repeatable(List<Map<String, Object>> rows) {
        return io.tesseraql.core.files.ExportModel.repeatable(rows, Map.of());
    }

    private static List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "alpha");
        first.put("qty", 1);
        rows.add(first);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("name", "beta");
        second.put("qty", 2);
        rows.add(second);
        return rows;
    }

    @Test
    void gridWriteAndReadRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), "items", null, null), streaming(rows()));

        List<Map<String, Object>> read = new ArrayList<>();
        codec.read(new ByteArrayInputStream(out.toByteArray()),
                new FileReadSpec(List.of(), true, "items", 1),
                (rowNumber, values) -> read.add(values));

        assertThat(read).hasSize(2);
        assertThat(read.get(0).get("name")).isEqualTo("alpha");
        assertThat(read.get(1).get("qty")).isEqualTo("2");
    }

    @Test
    void headerLabelsAndExplicitPositionsResolveColumns() throws Exception {
        // A workbook whose table starts at row 3, with localized headers.
        byte[] workbook = workbookWithTitleRows();

        List<Map<String, Object>> read = new ArrayList<>();
        codec.read(new ByteArrayInputStream(workbook),
                new FileReadSpec(List.of(
                        new ColumnMapping("productName", "商品名", null),
                        new ColumnMapping("qty", null, ColumnMapping.parseColumn("C"))),
                        true, null, 3),
                (rowNumber, values) -> read.add(values));

        assertThat(read).hasSize(2);
        assertThat(read.get(0).get("productName")).isEqualTo("alpha");
        assertThat(read.get(0).get("qty")).isEqualTo("1");
        assertThat(read.get(1).get("productName")).isEqualTo("beta");
    }

    @Test
    void placementModeWritesAtDeclaredPositionsWithTemplateStyles() throws Exception {
        Path template = writePlacementTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("name", null, ColumnMapping.parseColumn("B")),
                new ColumnMapping("qty", null, ColumnMapping.parseColumn("D"))),
                null, template, CellRef.parse("B5")), repeatable(rows()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            // The template's title block above the data area is untouched.
            assertThat(sheet.getRow(0).getCell(1).getStringCellValue()).isEqualTo("Order Report");
            // Data landed at B5/D5 and B6/D6.
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("alpha");
            assertThat(sheet.getRow(4).getCell(3).getNumericCellValue()).isEqualTo(1.0);
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).isEqualTo("beta");
            // The startCell row's template style (border) was applied to every data row.
            assertThat(sheet.getRow(5).getCell(1).getCellStyle().getBorderBottom())
                    .isEqualTo(BorderStyle.THIN);
        }
    }

    @Test
    void placementRefusesToWriteOverATemplateBandBelowTheDataArea() throws Exception {
        // A total band four rows under startCell: two rows fit, three do not. Placement writes
        // downward and never shifts, so the old behaviour overwrote the band's mapped columns and
        // left its label standing — a file that looks complete and is not
        // (docs/export-pipeline.md, decision 4).
        Path template = writePlacementTemplateWithTotalsAt(6);
        FileWriteSpec spec = new FileWriteSpec(List.of(
                new ColumnMapping("name", null, ColumnMapping.parseColumn("B")),
                new ColumnMapping("qty", null, ColumnMapping.parseColumn("D"))),
                null, template, CellRef.parse("B5"));

        assertThatCode(() -> codec.write(new ByteArrayOutputStream(), spec, repeatable(rows())))
                .as("two rows fit above the band")
                .doesNotThrowAnyException();

        List<Map<String, Object>> tooMany = new ArrayList<>(rows());
        tooMany.add(new LinkedHashMap<>(Map.of("name", "gamma", "qty", 3)));
        assertThatThrownBy(() -> codec.write(new ByteArrayOutputStream(), spec,
                repeatable(tooMany)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("row 7")
                .hasMessageContaining("2 rows")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2852");
    }

    @Test
    void typedColumnsBecomeRealDateAndNumberCellsWithFormats() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("held_on", java.sql.Timestamp.from(
                java.time.Instant.parse("2026-06-10T23:30:00Z")));
        row.put("fee", new java.math.BigDecimal("1234.5"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("held_on", null, null, "datetime", "yyyy/mm/dd hh:mm"),
                new ColumnMapping("fee", null, null, "number", "#,##0.00")),
                null, null, null, null, "Asia/Tokyo"),
                streaming(List.of(row)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            org.apache.poi.ss.usermodel.Cell date = sheet.getRow(1).getCell(0);
            // A real date cell in the transfer's time zone, carrying the declared cell format.
            assertThat(org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(date)).isTrue();
            assertThat(date.getLocalDateTimeCellValue())
                    .isEqualTo(java.time.LocalDateTime.of(2026, 6, 11, 8, 30));
            org.apache.poi.ss.usermodel.Cell fee = sheet.getRow(1).getCell(1);
            assertThat(fee.getNumericCellValue()).isEqualTo(1234.5);
            assertThat(fee.getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        }
    }

    @Test
    void jxlsTemplateRendersReportStyleOutput() throws Exception {
        Path template = writeJxlsTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template, null), repeatable(rows()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Item Report");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("alpha");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("beta");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(2.0);
        }
    }

    @Test
    void aMultisheetReportStreamsThroughTheFrameworksGroups() throws Exception {
        // The framework's groups, not jxls's groupBy: jxls groups by materializing, so a
        // multisheet report written that way would buffer every row again — the whole point of
        // decision 3 (docs/export-pipeline.md).
        Path template = writeMultisheetTemplate();
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(new LinkedHashMap<>(Map.of("dept", "sales", "name", "ann")));
        rows.add(new LinkedHashMap<>(Map.of("dept", "sales", "name", "bob")));
        rows.add(new LinkedHashMap<>(Map.of("dept", "ops", "name", "cat")));
        io.tesseraql.core.spool.FileTempStore store = new io.tesseraql.core.spool.FileTempStore(
                dir.resolve("spool"));
        try (io.tesseraql.core.files.SpooledRows spooled = io.tesseraql.core.files.SpooledRows
                .drain(store, rows.iterator())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            codec.write(out, new FileWriteSpec(List.of(), null, template, null, null, null, null,
                    "dept", null, false),
                    io.tesseraql.core.files.ExportModel.repeatable(spooled, Map.of()));

            try (XSSFWorkbook workbook = new XSSFWorkbook(
                    new ByteArrayInputStream(out.toByteArray()))) {
                assertThat(sheetNames(workbook)).contains("sales", "ops");
                assertThat(textOf(workbook.getSheet("sales"))).contains("sales", "ann", "bob");
                assertThat(textOf(workbook.getSheet("ops"))).contains("ops", "cat");
                // Each group's rows land on its own sheet and nowhere else.
                assertThat(textOf(workbook.getSheet("ops"))).doesNotContain("ann");
            }
        }
    }

    /** A multisheet template: one sheet per group, that group's rows written inside it. */
    @Test
    void aGridWritesANullCellBlankAndKeepsGoing() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(typedColumns(null, null, null),
                null, null, null, null, "Asia/Tokyo"), streaming(rowsWithANullRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            // Row 1 (after the header) is the full row - the control that typed cells wrote.
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("alpha");
            assertThat(sheet.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(1234.5);
            // Row 2 is the NULL row: every cell blank, never the text "null" or a zero.
            Row nulls = sheet.getRow(2);
            for (int i = 0; i < 3; i++) {
                Cell cell = nulls == null ? null : nulls.getCell(i);
                assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                        .as("NULL cell %d is blank", i).isTrue();
            }
            // Row 3 exists: the export went on past the NULL.
            assertThat(sheet.getRow(3).getCell(0).getStringCellValue()).isEqualTo("gamma");
        }
    }

    @Test
    void aPlacementWritesANullCellBlankAndKeepsGoing() throws Exception {
        Path template = writePlacementTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(typedColumns("B", "D", "F"),
                null, template, CellRef.parse("B5"), null, "Asia/Tokyo"),
                repeatable(rowsWithANullRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("alpha");
            Row nulls = sheet.getRow(5);
            for (int col : new int[]{1, 3, 5}) {
                Cell cell = nulls.getCell(col);
                assertThat(cell == null || cell.getCellType() == CellType.BLANK)
                        .as("NULL cell at column %d is blank", col).isTrue();
            }
            assertThat(sheet.getRow(6).getCell(1).getStringCellValue()).isEqualTo("gamma");
        }
    }

    /**
     * The control, not a guard (MEASUREMENT.md section 8 hazard 23): jxls report mode never
     * asks toZoned and rendered a NULL as an empty cell all along. Green before and after.
     */
    @Test
    void aJxlsReportRendersANullCellAsEmptyControl() throws Exception {
        Path template = writeJxlsTemplate();
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("name", "alpha");
        first.put("qty", null);
        rows.add(first);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template, null), repeatable(rows));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("alpha");
            Cell qty = sheet.getRow(1).getCell(1);
            assertThat(qty == null || qty.getCellType() == CellType.BLANK).isTrue();
        }
    }

    @Test
    void aGridWritesASqlTimeAsATimeCell() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                ColumnMapping.of("label"),
                ColumnMapping.of("starts_at"),
                new ColumnMapping("ends_at", null, null, null, "hh:mm"),
                ColumnMapping.of("opens_at"),
                ColumnMapping.of("closes_at")),
                null, null, null, null, "Asia/Tokyo"), streaming(shiftRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Cell starts = sheet.getRow(1).getCell(1);
            // A real time cell: the fraction of a day (not a date at an epoch, not text),
            // under a time format so it displays as a time, not as 0.9375.
            assertThat(starts.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(starts.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
            assertThat(DateUtil.isCellDateFormatted(starts)).isTrue();
            assertThat(starts.getCellStyle().getDataFormatString()).isEqualTo("hh:mm:ss");
            assertThat(starts.getLocalDateTimeCellValue().toLocalTime())
                    .isEqualTo(java.time.LocalTime.of(22, 30));
            // A declared format is the cell format, as for a date column.
            Cell ends = sheet.getRow(1).getCell(2);
            assertThat(ends.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
            assertThat(ends.getCellStyle().getDataFormatString()).isEqualTo("hh:mm");
            // Decision 12 on the workbook: an OffsetTime keeps its wall clock (22:30-05:00 under
            // Asia/Tokyo is not 12:30), and a LocalTime is the same time cell.
            assertThat(sheet.getRow(1).getCell(3).getNumericCellValue())
                    .as("OffsetTime 22:30-05:00 under Asia/Tokyo").isEqualTo(TWENTY_TWO_THIRTY);
            assertThat(sheet.getRow(1).getCell(4).getNumericCellValue())
                    .as("LocalTime 22:30").isEqualTo(TWENTY_TWO_THIRTY);
        }
    }

    @Test
    void aPlacementWritesASqlTimeAsATimeCell() throws Exception {
        Path template = writePlacementTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("label", null, ColumnMapping.parseColumn("B")),
                new ColumnMapping("starts_at", null, ColumnMapping.parseColumn("D")),
                new ColumnMapping("ends_at", null, ColumnMapping.parseColumn("F"), null, "hh:mm"),
                new ColumnMapping("opens_at", null, ColumnMapping.parseColumn("H"))),
                null, template, CellRef.parse("B5"), null, "Asia/Tokyo"), repeatable(shiftRow()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            Cell starts = sheet.getRow(4).getCell(3);
            assertThat(starts.getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(starts.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
            // No declared format: the template's prototype style, as it is, says how the serial
            // displays (General here), and its border survives on the time cell as on every cell.
            assertThat(starts.getCellStyle().getDataFormatString()).isEqualTo("General");
            assertThat(starts.getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
            // A declared format is the cell format over the prototype style, as in a grid.
            Cell ends = sheet.getRow(4).getCell(5);
            assertThat(ends.getNumericCellValue()).isEqualTo(TWENTY_TWO_THIRTY);
            assertThat(ends.getCellStyle().getDataFormatString()).isEqualTo("hh:mm");
            assertThat(ends.getCellStyle().getBorderBottom()).isEqualTo(BorderStyle.THIN);
            // Decision 12 in placement: an OffsetTime whose offset is not the export zone's
            // keeps its wall clock here too.
            assertThat(sheet.getRow(4).getCell(7).getNumericCellValue())
                    .as("OffsetTime 22:30-05:00 under Asia/Tokyo").isEqualTo(TWENTY_TWO_THIRTY);
        }
    }

    /**
     * The time cell holds the wall clock's fraction whatever the JVM zone and the export zone:
     * the Excel half of the core guard, and the only guard that sees a writer zoning the Time
     * as an instant. Sequential JUnit; the default restored in finally.
     */
    @Test
    void aGridTimeCellKeepsItsWallClockUnderEveryJvmAndExportZone() throws Exception {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String jvmZone : new String[]{"Asia/Tokyo", "America/New_York", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                java.sql.Time time = java.sql.Time.valueOf("22:30:00");
                for (String exportZone : new String[]{"UTC", "Asia/Tokyo"}) {
                    assertThat(cellAt(grid(time, exportZone), 1, 0))
                            .as("jvm %s export %s", jvmZone, exportZone)
                            .isEqualTo(TWENTY_TWO_THIRTY);
                }
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /** A LocalTime's fraction of a second reaches the serial (a DuckDB time(6) keeps it). */
    @Test
    void aGridTimeCellKeepsAFractionOfASecond() throws Exception {
        assertThat(cellAt(grid(java.time.LocalTime.of(22, 30, 0, 500_000_000), "UTC"), 1, 0))
                .isCloseTo(TWENTY_TWO_THIRTY + 0.5 / 86_400d, within(1e-12));
    }

    /** The typed columns every NULL guard declares: text, typed number, typed datetime. */
    private static List<ColumnMapping> typedColumns(String namePos, String feePos,
            String heldPos) {
        return List.of(
                new ColumnMapping("name", null, pos(namePos), null, null),
                new ColumnMapping("fee", null, pos(feePos), "number", "#,##0.00"),
                new ColumnMapping("held_on", null, pos(heldPos), "datetime", "yyyy/mm/dd hh:mm"));
    }

    private static Integer pos(String column) {
        return column == null ? null : ColumnMapping.parseColumn(column);
    }

    /**
     * Three rows: a full one, one whose every mapped cell is NULL (text, typed number, typed
     * datetime), and one after it - so a codec that dies on the NULL never writes "gamma". Built
     * with LinkedHashMap: Map.of refuses a null value (a fixture hazard, red for the wrong
     * reason).
     */
    private static List<Map<String, Object>> rowsWithANullRow() {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("name", "alpha");
        full.put("fee", new java.math.BigDecimal("1234.5"));
        full.put("held_on", java.sql.Timestamp.from(
                java.time.Instant.parse("2026-06-10T23:30:00Z")));
        rows.add(full);
        Map<String, Object> nulls = new LinkedHashMap<>();
        nulls.put("name", null);
        nulls.put("fee", null);
        nulls.put("held_on", null);
        rows.add(nulls);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("name", "gamma");
        after.put("fee", new java.math.BigDecimal("3"));
        after.put("held_on", java.sql.Timestamp.from(
                java.time.Instant.parse("2026-06-12T00:00:00Z")));
        rows.add(after);
        return rows;
    }

    /**
     * One row of every time-of-day shape at 22:30: the driver's java.sql.Time twice (untyped,
     * and under a declared format), an OffsetTime whose offset is NOT the export zone's, and
     * a LocalTime.
     */
    private static List<Map<String, Object>> shiftRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "late");
        row.put("starts_at", java.sql.Time.valueOf("22:30:00"));
        row.put("ends_at", java.sql.Time.valueOf("22:30:00"));
        row.put("opens_at", java.time.OffsetTime.of(22, 30, 0, 0,
                java.time.ZoneOffset.ofHours(-5)));
        row.put("closes_at", java.time.LocalTime.of(22, 30));
        return List.of(row);
    }

    private byte[] grid(Object value, String timezone) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("t", value);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(ColumnMapping.of("t")),
                null, null, null, null, timezone), streaming(List.of(row)));
        return out.toByteArray();
    }

    private static double cellAt(byte[] workbookBytes, int row, int col) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(workbookBytes))) {
            return workbook.getSheetAt(0).getRow(row).getCell(col).getNumericCellValue();
        }
    }

    private Path writeMultisheetTemplate() throws Exception {
        Path template = dir.resolve("by-dept.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("dept");
            Row anchor = sheet.createRow(0);
            anchor.createCell(0).setCellValue("Report");
            Row outer = sheet.createRow(1);
            outer.createCell(0).setCellValue("${g.key}");
            Row inner = sheet.createRow(2);
            inner.createCell(0).setCellValue("${r.name}");
            comment(sheet, anchor.getCell(0), "jx:area(lastCell=\"A3\")");
            comment(sheet, outer.getCell(0),
                    "jx:each(items=\"groups\" var=\"g\" multisheet=\"groupKeys\""
                            + " lastCell=\"A3\")");
            comment(sheet, inner.getCell(0),
                    "jx:each(items=\"g.rows\" var=\"r\" lastCell=\"A3\")");
            try (OutputStream out = Files.newOutputStream(template)) {
                workbook.write(out);
            }
        }
        return template;
    }

    private static List<String> sheetNames(XSSFWorkbook workbook) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            names.add(workbook.getSheetName(i));
        }
        return names;
    }

    /** Every string cell on the sheet, so a template's layout can move without the test moving. */
    private static String textOf(Sheet sheet) {
        StringBuilder text = new StringBuilder();
        if (sheet == null) {
            return "";
        }
        for (Row row : sheet) {
            for (org.apache.poi.ss.usermodel.Cell cell : row) {
                if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.STRING) {
                    text.append(cell.getStringCellValue()).append('\n');
                }
            }
        }
        return text.toString();
    }

    /** Two title rows, a localized header row at row 3, then two data rows. */
    private static byte[] workbookWithTitleRows() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("受注");
            sheet.createRow(0).createCell(0).setCellValue("受注一覧");
            sheet.createRow(1).createCell(0).setCellValue("2026-06");
            Row header = sheet.createRow(2);
            header.createCell(0).setCellValue("商品名");
            header.createCell(1).setCellValue("備考");
            header.createCell(2).setCellValue("数量");
            Row first = sheet.createRow(3);
            first.createCell(0).setCellValue("alpha");
            first.createCell(2).setCellValue(1);
            Row second = sheet.createRow(4);
            second.createCell(0).setCellValue("beta");
            second.createCell(2).setCellValue(2);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /** A styled placement template: title block, headers, and a bordered prototype row at B5. */
    private Path writePlacementTemplate() throws Exception {
        Path template = dir.resolve("orders.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("orders");
            sheet.createRow(0).createCell(1).setCellValue("Order Report");
            Row header = sheet.createRow(3);
            header.createCell(1).setCellValue("Name");
            header.createCell(3).setCellValue("Qty");
            CellStyle bordered = workbook.createCellStyle();
            bordered.setBorderBottom(BorderStyle.THIN);
            Row prototype = sheet.createRow(4);
            prototype.createCell(1).setCellStyle(bordered);
            prototype.createCell(3).setCellStyle(bordered);
            prototype.createCell(5).setCellStyle(bordered);
            try (OutputStream out = Files.newOutputStream(template)) {
                workbook.write(out);
            }
        }
        return template;
    }

    /** The placement template with a totals band at the given 0-based row, under the data area. */
    private Path writePlacementTemplateWithTotalsAt(int totalsRow) throws Exception {
        Path template = dir.resolve("orders-with-totals.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("orders");
            sheet.createRow(0).createCell(1).setCellValue("Order Report");
            Row header = sheet.createRow(3);
            header.createCell(1).setCellValue("Name");
            header.createCell(3).setCellValue("Qty");
            sheet.createRow(4);
            Row totals = sheet.createRow(totalsRow);
            totals.createCell(1).setCellValue("Total");
            totals.createCell(3).setCellFormula("SUM(D5:D" + totalsRow + ")");
            try (OutputStream out = Files.newOutputStream(template)) {
                workbook.write(out);
            }
        }
        return template;
    }

    /** A minimal jxls template: a title row plus a jx:each region over {@code rows}. */
    private Path writeJxlsTemplate() throws Exception {
        Path template = dir.resolve("report.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("report");
            Row title = sheet.createRow(0);
            title.createCell(0).setCellValue("Item Report");
            Row each = sheet.createRow(1);
            each.createCell(0).setCellValue("${r.name}");
            each.createCell(1).setCellValue("${r.qty}");
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
        CreationHelper helper = sheet.getWorkbook().getCreationHelper();
        Drawing<?> drawing = sheet.createDrawingPatriarch();
        ClientAnchor anchor = helper.createClientAnchor();
        anchor.setCol1(cell.getColumnIndex());
        anchor.setRow1(cell.getRowIndex());
        anchor.setCol2(cell.getColumnIndex() + 3);
        anchor.setRow2(cell.getRowIndex() + 3);
        Comment comment = drawing.createCellComment(anchor);
        comment.setString(helper.createRichTextString(text));
        cell.setCellComment(comment);
    }
}
