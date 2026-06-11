package io.tesseraql.excel;

import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.builder.JxlsOutput;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;

/**
 * The optional Excel (xlsx) codec (design ch. 28): on export a jxls-annotated workbook colocated
 * with the route renders report-style output ({@code jx:area}/{@code jx:each} notes, rows exposed
 * as {@code rows}); without a template a plain streamed grid (header plus rows) is written. On
 * import POI reads the rows, mapping cells like the CSV codec - positional against the declared
 * columns without a header row, by header otherwise.
 */
public final class JxlsFileCodec implements FileCodec {

    @Override
    public String format() {
        return "excel";
    }

    @Override
    public String contentType() {
        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    @Override
    public String extension() {
        return ".xlsx";
    }

    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
        DataFormatter formatter = new DataFormatter();
        try (Workbook workbook = new XSSFWorkbook(in)) {
            Sheet sheet = spec.sheet() == null || spec.sheet().isBlank()
                    ? workbook.getSheetAt(0)
                    : workbook.getSheet(spec.sheet());
            if (sheet == null) {
                throw new IllegalArgumentException("No sheet named '" + spec.sheet() + "'");
            }
            List<String> columns = new ArrayList<>(spec.columns());
            long rowNumber = 0;
            boolean first = true;
            for (Row row : sheet) {
                if (first && spec.headerRow()) {
                    first = false;
                    if (columns.isEmpty()) {
                        for (Cell cell : row) {
                            columns.add(formatter.formatCellValue(cell));
                        }
                    }
                    continue;
                }
                first = false;
                rowNumber++;
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = row.getCell(i);
                    values.put(columns.get(i),
                            cell == null ? null : formatter.formatCellValue(cell));
                }
                handler.row(rowNumber, values);
            }
        }
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException {
        if (spec.template() != null && Files.isRegularFile(spec.template())) {
            writeWithTemplate(out, spec, rows);
        } else {
            writeGrid(out, spec, rows);
        }
    }

    /** Report-style output: the jxls template iterates the materialized rows as {@code rows}. */
    private static void writeWithTemplate(OutputStream out, FileWriteSpec spec,
            Iterator<Map<String, Object>> rows) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        rows.forEachRemaining(data::add);
        // jxls adds its loop variables to the context, so the map must be mutable.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("rows", data);
        try (InputStream template = Files.newInputStream(spec.template())) {
            JxlsPoiTemplateFillerBuilder.newInstance()
                    .withTemplate(template)
                    .build()
                    .fill(context, new JxlsOutput() {
                        @Override
                        public OutputStream getOutputStream() {
                            return out;
                        }
                    });
        }
    }

    /** Plain tabular output, streamed through POI's SXSSF window so memory stays bounded. */
    private static void writeGrid(OutputStream out, FileWriteSpec spec,
            Iterator<Map<String, Object>> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet(
                    spec.sheet() == null || spec.sheet().isBlank() ? "data" : spec.sheet());
            List<String> columns = new ArrayList<>(spec.columns());
            int rowIndex = 0;
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                if (columns.isEmpty()) {
                    columns.addAll(row.keySet());
                }
                if (rowIndex == 0) {
                    Row header = sheet.createRow(rowIndex++);
                    for (int i = 0; i < columns.size(); i++) {
                        header.createCell(i).setCellValue(columns.get(i));
                    }
                }
                Row target = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    setCell(target.createCell(i), row.get(columns.get(i)));
                }
            }
            workbook.write(out);
            workbook.dispose();
        }
    }

    private static void setCell(Cell cell, Object value) {
        switch (value) {
            case null -> cell.setBlank();
            case Number number -> cell.setCellValue(number.doubleValue());
            case Boolean bool -> cell.setCellValue(bool);
            default -> cell.setCellValue(String.valueOf(value));
        }
    }
}
