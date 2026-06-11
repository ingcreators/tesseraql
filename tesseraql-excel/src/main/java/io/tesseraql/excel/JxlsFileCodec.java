package io.tesseraql.excel;

import io.tesseraql.core.files.CellRef;
import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.core.files.Tables;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.builder.JxlsOutput;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;

/**
 * The optional Excel (xlsx) codec (design ch. 28). Imports read rows with POI using the shared
 * column resolution - header labels (or explicit positions) name the values, {@code startRow}
 * skips title rows. Exports have three modes: a plain streamed grid without a template;
 * placement mode (template plus {@code startCell}) where the YAML declares where each column
 * lands and the template carries only layout and styles - the {@code startCell} row acts as the
 * style prototype for every data row; and a jx:-annotated jxls report template (advanced) that
 * drives its own iteration over {@code rows}.
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
            Sheet sheet = sheet(workbook, spec.sheet());
            int next = spec.startRow() - 1;
            List<String> header = null;
            if (spec.headerRow()) {
                header = new ArrayList<>();
                Row headerRow = sheet.getRow(next++);
                if (headerRow != null) {
                    for (Cell cell : headerRow) {
                        header.add(formatter.formatCellValue(cell));
                    }
                }
            }
            List<ColumnMapping> columns = spec.columns().isEmpty() && header != null
                    ? header.stream().map(ColumnMapping::of).toList()
                    : spec.columns();
            int[] positions = Tables.positions(columns, header);
            long rowNumber = 0;
            for (int index = next; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null) {
                    continue;
                }
                rowNumber++;
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = positions[i] < 0 ? null : row.getCell(positions[i]);
                    values.put(columns.get(i).name(),
                            cell == null ? null : formatter.formatCellValue(cell));
                }
                handler.row(rowNumber, values);
            }
        }
    }

    private static Sheet sheet(Workbook workbook, String name) {
        Sheet sheet = name == null || name.isBlank()
                ? workbook.getSheetAt(0) : workbook.getSheet(name);
        if (sheet == null) {
            throw new IllegalArgumentException("No sheet named '" + name + "'");
        }
        return sheet;
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException {
        boolean hasTemplate = spec.template() != null && Files.isRegularFile(spec.template());
        if (hasTemplate && spec.startCell() != null) {
            writePlacement(out, spec, rows);
        } else if (hasTemplate) {
            writeWithJxlsTemplate(out, spec, rows);
        } else {
            writeGrid(out, spec, rows);
        }
    }

    /**
     * Placement mode: data rows land at {@code startCell}, each column at its declared position
     * (or sequentially from the start column). The template's {@code startCell} row provides the
     * per-column cell styles, so number formats and borders are designed in Excel.
     */
    private static void writePlacement(OutputStream out, FileWriteSpec spec,
            Iterator<Map<String, Object>> rows) throws IOException {
        try (InputStream template = Files.newInputStream(spec.template());
                XSSFWorkbook workbook = new XSSFWorkbook(template)) {
            Sheet sheet = sheet(workbook, spec.sheet());
            CellRef start = spec.startCell();
            ZoneId zone = io.tesseraql.core.files.ColumnValues.zone(spec.timezone());
            List<ColumnMapping> columns = new ArrayList<>(spec.columns());
            int[] positions = null;
            CellStyle[] styles = null;
            int rowIndex = start.row();
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                if (columns.isEmpty()) {
                    row.keySet().forEach(key -> columns.add(ColumnMapping.of(key)));
                }
                if (positions == null) {
                    positions = placementPositions(columns, start.col());
                    styles = columnStyles(workbook, columns,
                            prototypeStyles(sheet, start.row(), positions));
                }
                Row target = sheet.getRow(rowIndex);
                if (target == null) {
                    target = sheet.createRow(rowIndex);
                }
                rowIndex++;
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = target.getCell(positions[i]);
                    if (cell == null) {
                        cell = target.createCell(positions[i]);
                    }
                    if (styles[i] != null) {
                        cell.setCellStyle(styles[i]);
                    }
                    setCell(cell, row.get(columns.get(i).name()), zone);
                }
            }
            workbook.write(out);
        }
    }

    /**
     * The per-column cell styles: the template prototype, overlaid with the column's declared
     * Excel format when one is set (so YAML formats win over the template's placeholder format).
     */
    private static CellStyle[] columnStyles(org.apache.poi.ss.usermodel.Workbook workbook,
            List<ColumnMapping> columns, CellStyle[] prototypes) {
        CellStyle[] styles = new CellStyle[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            String format = columns.get(i).format();
            if (format == null || format.isBlank()) {
                styles[i] = prototypes[i];
                continue;
            }
            CellStyle style = workbook.createCellStyle();
            if (prototypes[i] != null) {
                style.cloneStyleFrom(prototypes[i]);
            }
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            styles[i] = style;
        }
        return styles;
    }

    /** Explicit positions win; the rest fill sequentially from the start column. */
    private static int[] placementPositions(List<ColumnMapping> columns, int startCol) {
        int[] positions = new int[columns.size()];
        int next = startCol;
        for (int i = 0; i < columns.size(); i++) {
            Integer index = columns.get(i).index();
            positions[i] = index != null ? index : next;
            next = positions[i] + 1;
        }
        return positions;
    }

    /** The styles of the template's first data row, applied to every written row. */
    private static CellStyle[] prototypeStyles(Sheet sheet, int startRow, int[] positions) {
        CellStyle[] styles = new CellStyle[positions.length];
        Row prototype = sheet.getRow(startRow);
        if (prototype != null) {
            for (int i = 0; i < positions.length; i++) {
                Cell cell = prototype.getCell(positions[i]);
                styles[i] = cell == null ? null : cell.getCellStyle();
            }
        }
        return styles;
    }

    /** Report mode: the jxls template iterates the materialized rows as {@code rows}. */
    private static void writeWithJxlsTemplate(OutputStream out, FileWriteSpec spec,
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
            ZoneId zone = io.tesseraql.core.files.ColumnValues.zone(spec.timezone());
            List<ColumnMapping> columns = new ArrayList<>(spec.columns());
            CellStyle[] styles = null;
            int rowIndex = 0;
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                if (columns.isEmpty()) {
                    row.keySet().forEach(key -> columns.add(ColumnMapping.of(key)));
                }
                if (rowIndex == 0) {
                    styles = columnStyles(workbook, columns, new CellStyle[columns.size()]);
                    Row header = sheet.createRow(rowIndex++);
                    for (int i = 0; i < columns.size(); i++) {
                        header.createCell(i).setCellValue(columns.get(i).effectiveHeader());
                    }
                }
                Row target = sheet.createRow(rowIndex++);
                for (int i = 0; i < columns.size(); i++) {
                    Cell cell = target.createCell(i);
                    if (styles[i] != null) {
                        cell.setCellStyle(styles[i]);
                    }
                    setCell(cell, row.get(columns.get(i).name()), zone);
                }
            }
            workbook.write(out);
            workbook.dispose();
        }
    }

    /** Writes a typed cell: temporals become real date cells, numbers numeric cells. */
    private static void setCell(Cell cell, Object value, ZoneId zone) {
        java.time.ZonedDateTime temporal =
                io.tesseraql.core.files.ColumnValues.toZoned(value, zone);
        if (temporal != null) {
            cell.setCellValue(temporal.toLocalDateTime());
            return;
        }
        switch (value) {
            case null -> cell.setBlank();
            case Number number -> cell.setCellValue(number.doubleValue());
            case Boolean bool -> cell.setCellValue(bool);
            default -> cell.setCellValue(String.valueOf(value));
        }
    }
}
