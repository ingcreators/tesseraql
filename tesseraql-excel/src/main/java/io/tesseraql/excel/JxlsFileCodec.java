package io.tesseraql.excel;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.builder.JxlsOutput;
import org.jxls.transform.poi.JxlsPoiTemplateFillerBuilder;

/**
 * The optional Excel (xlsx) codec (design ch. 28). Imports stream rows through fastexcel-reader
 * using the shared column resolution - header labels (or explicit positions) name the values,
 * {@code startRow} skips title rows, and native date/number cells of typed columns arrive typed.
 * Exports have three modes: a plain grid streamed through fastexcel's writer;
 * placement mode (template plus {@code startCell}) where the YAML declares where each column
 * lands and the template carries only layout and styles - the {@code startCell} row acts as the
 * style prototype for every data row; and a jx:-annotated jxls report template (advanced) that
 * drives its own iteration over {@code rows}.
 */
public final class JxlsFileCodec implements FileCodec {

    /** TQL-LD-2852: placement data reached template content below the data area. */
    private static final TqlErrorCode PLACEMENT_COLLISION = new TqlErrorCode(TqlDomain.LD, 2852);

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

    /**
     * Streams the workbook through fastexcel-reader, so imports of any size stay off-heap (POI's
     * XSSF model would materialize the whole workbook). Native date/number cells of typed
     * columns surface as typed values directly - no display-format round trip.
     */
    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
        try (org.dhatim.fastexcel.reader.ReadableWorkbook workbook = new org.dhatim.fastexcel.reader.ReadableWorkbook(
                in)) {
            org.dhatim.fastexcel.reader.Sheet sheet = spec.sheet() == null || spec.sheet().isBlank()
                    ? workbook.getFirstSheet()
                    : workbook.findSheet(spec.sheet())
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "No sheet named '" + spec.sheet() + "'"));
            try (java.util.stream.Stream<org.dhatim.fastexcel.reader.Row> rows = sheet
                    .openStream()) {
                java.util.Iterator<org.dhatim.fastexcel.reader.Row> iterator = rows.iterator();
                for (int skip = 1; skip < spec.startRow() && iterator.hasNext(); skip++) {
                    iterator.next();
                }
                List<String> header = null;
                if (spec.headerRow() && iterator.hasNext()) {
                    org.dhatim.fastexcel.reader.Row headerRow = iterator.next();
                    header = new ArrayList<>();
                    for (int i = 0; i < headerRow.getCellCount(); i++) {
                        header.add(text(headerRow.getCell(i)));
                    }
                }
                boolean declared = !spec.columns().isEmpty();
                List<ColumnMapping> columns = declared || header == null
                        ? spec.columns()
                        : header.stream().map(ColumnMapping::of).toList();
                int[] positions = Tables.positions(columns, header);
                if (declared) {
                    Tables.requireDeclaredHeadersMatched(columns, header, positions);
                }
                long rowNumber = 0;
                while (iterator.hasNext()) {
                    org.dhatim.fastexcel.reader.Row row = iterator.next();
                    rowNumber++;
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size(); i++) {
                        org.dhatim.fastexcel.reader.Cell cell = positions[i] < 0
                                || positions[i] >= row.getCellCount()
                                        ? null
                                        : row.getCell(positions[i]);
                        values.put(columns.get(i).name(), value(columns.get(i), cell));
                    }
                    handler.row(rowNumber, values);
                }
            }
        }
    }

    /** A typed column reads native cells natively; everything else surfaces as text. */
    private static Object value(ColumnMapping column,
            org.dhatim.fastexcel.reader.Cell cell) {
        if (cell == null
                || cell.getType() == org.dhatim.fastexcel.reader.CellType.EMPTY) {
            return null;
        }
        boolean numericCell = cell.getType() == org.dhatim.fastexcel.reader.CellType.NUMBER;
        if (numericCell && ("date".equals(column.type()) || "datetime".equals(column.type()))) {
            return cell.asDate();
        }
        if (numericCell && "number".equals(column.type())) {
            return cell.asNumber();
        }
        return text(cell);
    }

    private static String text(org.dhatim.fastexcel.reader.Cell cell) {
        if (cell == null) {
            return null;
        }
        return switch (cell.getType()) {
            case EMPTY -> null;
            case STRING -> cell.asString();
            // General-format semantics: 34 reads as "34", not the stored "34.0".
            case NUMBER -> cell.asNumber().stripTrailingZeros().toPlainString();
            default -> cell.getRawValue();
        };
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec,
            io.tesseraql.core.files.ExportModel model) throws IOException {
        boolean hasTemplate = spec.template() != null && Files.isRegularFile(spec.template());
        if (hasTemplate && spec.startCell() != null) {
            // Placement walks the rows once, but its mode is declared as buffering because the
            // template workbook is held whole — so the re-readable source is the one it is given.
            writePlacement(out, spec, model.repeatableRows().iterator());
        } else if (hasTemplate) {
            writeWithJxlsTemplate(out, spec, model);
        } else {
            writeGrid(out, spec, model.rows());
        }
    }

    /**
     * The grid streams through fastexcel's writer; both template modes hold the workbook (and, in
     * report mode, the rows) in memory, so they are capped (docs/export-pipeline.md, decision 6).
     */
    @Override
    public boolean streams(FileWriteSpec spec) {
        return spec.template() == null;
    }

    private static Sheet sheet(Workbook workbook, String name) {
        Sheet sheet = name == null || name.isBlank()
                ? workbook.getSheetAt(0)
                : workbook.getSheet(name);
        if (sheet == null) {
            throw new IllegalArgumentException("No sheet named '" + name + "'");
        }
        return sheet;
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
            // Placement writes downward and never shifts anything: a template band below the data
            // area would be overwritten in the mapped columns while its labels survived, which
            // reads as a plausible file rather than a broken one (docs/export-pipeline.md,
            // decision 4). The template's used range is small, so the first occupied row is known
            // before a byte is written.
            int firstOccupiedBelow = firstOccupiedRowBelow(sheet, start.row());
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
                if (rowIndex >= firstOccupiedBelow) {
                    throw new TqlException(PLACEMENT_COLLISION,
                            "placement export reached row " + (firstOccupiedBelow + 1)
                                    + " of sheet '" + sheet.getSheetName() + "', which the"
                                    + " template already uses - the data area below startCell "
                                    + spec.startCell() + " holds "
                                    + (firstOccupiedBelow - start.row()) + " rows (move the"
                                    + " band down, or split the export with splitBy:)");
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

    /**
     * The first row below {@code startRow} carrying any non-blank cell, or
     * {@link Integer#MAX_VALUE} when the sheet is clear all the way down. A row that only carries
     * styles is not occupied: templates commonly format a range well past their content, and
     * refusing to write into formatting would reject the ordinary case.
     */
    private static int firstOccupiedRowBelow(Sheet sheet, int startRow) {
        for (int index = startRow + 1; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null) {
                continue;
            }
            for (int col = row.getFirstCellNum(); col >= 0 && col < row.getLastCellNum(); col++) {
                Cell cell = row.getCell(col);
                if (cell != null && cell.getCellType() != CellType.BLANK) {
                    return index;
                }
            }
        }
        return Integer.MAX_VALUE;
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

    /**
     * Report mode: the jxls template drives its own iteration over {@code rows}. jxls's
     * EachCommand iterates an Iterable, so the re-readable row set goes in as it is — a template
     * may walk it more than once, and nothing is collected into a list to allow that.
     */
    private static void writeWithJxlsTemplate(OutputStream out, FileWriteSpec spec,
            io.tesseraql.core.files.ExportModel model) throws IOException {
        // jxls adds its loop variables to the context, so the map must be mutable. The export's
        // other declared sources go in under their own names (docs/export-pipeline.md, dec. 2).
        Map<String, Object> context = new LinkedHashMap<>(model.values());
        context.put("rows", model.repeatableRows());
        org.jxls.builder.JxlsStreaming streaming = org.jxls.builder.JxlsStreaming.STREAMING_ON;
        if (spec.groupBy() != null && !spec.groupBy().isBlank()) {
            // Grouping is the framework's, not the template's: jxls's own groupBy materializes
            // (groupIterable returns a Collection of GroupData holding Collections), so a
            // multisheet report written against it would buffer every row again. A template that
            // walks `groups` and each group's `rows` calls neither (decision 3).
            io.tesseraql.core.files.ExportGroups groups = model.groupedBy(spec.groupBy());
            context.put("groups", groups);
            List<String> sheetNames = groups.keys().stream().map(String::valueOf).toList();
            context.put("groupKeys", sheetNames);
            // Only the generated sheets stream. Streaming every sheet includes the template's
            // own, and jxls reads that one to know what to write — a multisheet report came out
            // with the right sheet names and nothing in them.
            streaming = sheetNames.isEmpty()
                    ? org.jxls.builder.JxlsStreaming.STREAMING_OFF
                    : org.jxls.builder.JxlsStreaming.streamingWithGivenSheets(
                            new java.util.LinkedHashSet<>(sheetNames));
        }
        try (InputStream template = Files.newInputStream(spec.template())) {
            JxlsPoiTemplateFillerBuilder.newInstance()
                    .withTemplate(template)
                    // SXSSF output: the workbook stops being held whole, which is the other half
                    // of streaming a report — the re-readable row set was the first (decision 9).
                    .withStreaming(streaming)
                    .build()
                    .fill(context, new JxlsOutput() {
                        @Override
                        public OutputStream getOutputStream() {
                            return out;
                        }
                    });
        }
    }

    /**
     * Plain tabular output through fastexcel's streaming writer - no POI temp-file repackaging.
     * Temporal and numeric values become typed cells; a column's declared format (or a default
     * date format for temporals) applies as the cell format.
     */
    private static void writeGrid(OutputStream out, FileWriteSpec spec,
            Iterator<Map<String, Object>> rows) throws IOException {
        // try-with-resources finishes the workbook even when a row iterator fails mid-write.
        try (org.dhatim.fastexcel.Workbook workbook = new org.dhatim.fastexcel.Workbook(out,
                "TesseraQL", "1.0")) {
            org.dhatim.fastexcel.Worksheet sheet = workbook.newWorksheet(
                    spec.sheet() == null || spec.sheet().isBlank() ? "data" : spec.sheet());
            ZoneId zone = io.tesseraql.core.files.ColumnValues.zone(spec.timezone());
            List<ColumnMapping> columns = new ArrayList<>(spec.columns());
            int rowIndex = 0;
            while (rows.hasNext()) {
                Map<String, Object> row = rows.next();
                if (columns.isEmpty()) {
                    row.keySet().forEach(key -> columns.add(ColumnMapping.of(key)));
                }
                if (rowIndex == 0) {
                    for (int i = 0; i < columns.size(); i++) {
                        sheet.value(rowIndex, i, columns.get(i).effectiveHeader());
                    }
                    rowIndex++;
                }
                for (int i = 0; i < columns.size(); i++) {
                    writeValue(sheet, rowIndex, i, columns.get(i),
                            row.get(columns.get(i).name()), zone);
                }
                rowIndex++;
            }
        }
    }

    /** Writes one typed grid cell, applying the column's (or the temporal default) format. */
    private static void writeValue(org.dhatim.fastexcel.Worksheet sheet, int rowIndex,
            int colIndex, ColumnMapping column, Object value, ZoneId zone) {
        java.time.ZonedDateTime temporal = io.tesseraql.core.files.ColumnValues.toZoned(value,
                zone);
        String format = column.format();
        if (temporal != null) {
            sheet.value(rowIndex, colIndex, temporal.toLocalDateTime());
            // A date cell without a format renders as a raw serial number; default sensibly.
            sheet.style(rowIndex, colIndex)
                    .format(format == null || format.isBlank()
                            ? "yyyy-mm-dd hh:mm"
                            : format)
                    .set();
            return;
        }
        switch (value) {
            case null -> {
            }
            case Number number -> {
                sheet.value(rowIndex, colIndex, number);
                if (format != null && !format.isBlank()) {
                    sheet.style(rowIndex, colIndex).format(format).set();
                }
            }
            case Boolean bool -> sheet.value(rowIndex, colIndex, bool);
            default -> sheet.value(rowIndex, colIndex, String.valueOf(value));
        }
    }

    /** Writes a typed cell: temporals become real date cells, numbers numeric cells. */
    private static void setCell(Cell cell, Object value, ZoneId zone) {
        java.time.ZonedDateTime temporal = io.tesseraql.core.files.ColumnValues.toZoned(value,
                zone);
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
