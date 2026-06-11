package io.tesseraql.excel;

import static org.assertj.core.api.Assertions.assertThat;

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
        codec.write(out, new FileWriteSpec(List.of(), "items", null), rows().iterator());

        List<Map<String, Object>> read = new ArrayList<>();
        codec.read(new ByteArrayInputStream(out.toByteArray()),
                new FileReadSpec(List.of(), true, "items"),
                (rowNumber, values) -> read.add(values));

        assertThat(read).hasSize(2);
        assertThat(read.get(0).get("name")).isEqualTo("alpha");
        assertThat(read.get(1).get("qty")).isEqualTo("2");
    }

    @Test
    void declaredColumnsMapPositionallyWithoutAHeaderRow() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, null), rows().iterator());

        // Read the same file but treat every row as data, naming cells positionally.
        List<Map<String, Object>> read = new ArrayList<>();
        codec.read(new ByteArrayInputStream(out.toByteArray()),
                new FileReadSpec(List.of("col1", "col2"), false, null),
                (rowNumber, values) -> read.add(values));

        assertThat(read).hasSize(3);
        assertThat(read.get(0).get("col1")).isEqualTo("name");
        assertThat(read.get(1).get("col1")).isEqualTo("alpha");
    }

    @Test
    void jxlsTemplateRendersReportStyleOutput() throws Exception {
        Path template = writeTemplate();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(), null, template), rows().iterator());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(out.toByteArray()))) {
            Sheet sheet = workbook.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Item Report");
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).isEqualTo("alpha");
            assertThat(sheet.getRow(2).getCell(0).getStringCellValue()).isEqualTo("beta");
            assertThat(sheet.getRow(2).getCell(1).getNumericCellValue()).isEqualTo(2.0);
        }
    }

    /** A minimal jxls template: a title row plus a jx:each region over {@code rows}. */
    private Path writeTemplate() throws Exception {
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
