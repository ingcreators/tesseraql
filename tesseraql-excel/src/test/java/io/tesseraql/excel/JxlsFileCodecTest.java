package io.tesseraql.excel;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JxlsFileCodecTest {

    @TempDir
    Path dir;

    private final JxlsFileCodec codec = new JxlsFileCodec();

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
        codec.write(out, new FileWriteSpec(List.of(), "items", null, null), rows().iterator());

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
                null, template, CellRef.parse("B5")), rows().iterator());

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
                List.of(row).iterator());

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
        codec.write(out, new FileWriteSpec(List.of(), null, template, null), rows().iterator());

        try (XSSFWorkbook workbook = new XSSFWorkbook(
                new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Item Report");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("alpha");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("beta");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(2.0);
        }
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
            comment(sheet, each.getCell(0), "jx:each(items=\"rows\" var=\"r\" lastCell=\"B2\")");
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
