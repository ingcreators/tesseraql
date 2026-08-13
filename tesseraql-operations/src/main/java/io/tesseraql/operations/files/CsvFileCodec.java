package io.tesseraql.operations.files;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.core.files.TabularReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

/**
 * The built-in CSV codec (design ch. 28), RFC 4180 via Apache Commons CSV: UTF-8, quoted fields.
 * On read the declared columns resolve to positions through the header row (matching each
 * column's header label) or their declared order, with explicit {@code column:} positions taking
 * precedence; {@code startRow} skips leading non-table rows. On write the declared columns
 * select, order and label the output (the row's own keys otherwise).
 */
public final class CsvFileCodec implements FileCodec {

    @Override
    public String format() {
        return "csv";
    }

    @Override
    public String contentType() {
        return "text/csv; charset=utf-8";
    }

    @Override
    public String extension() {
        return ".csv";
    }

    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(in, StandardCharsets.UTF_8), CSVFormat.RFC4180)) {
            TabularReader.read(parser.iterator(), spec, CELLS, handler);
        }
    }

    /** CSV cell access: every cell is text, and a position past the record's end reads null. */
    private static final TabularReader.Cells<CSVRecord> CELLS = new TabularReader.Cells<CSVRecord>() {

        @Override
        public List<String> header(CSVRecord row) {
            List<String> header = new ArrayList<>();
            for (String cell : row) {
                header.add(cell);
            }
            return header;
        }

        @Override
        public Object value(CSVRecord row, int position, ColumnMapping column) {
            return position >= 0 && position < row.size() ? row.get(position) : null;
        }
    };

    @Override
    // The printer is deliberately not closed: closing it would close the caller-owned stream;
    // the codec contract is flush-only.
    @SuppressWarnings("resource")
    public void write(OutputStream out, FileWriteSpec spec,
            io.tesseraql.core.files.ExportModel model) throws IOException {
        Iterator<Map<String, Object>> rows = model.rows();
        CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.RFC4180);
        java.util.Locale locale = io.tesseraql.core.files.ColumnValues.locale(spec.locale());
        java.time.ZoneId zone = io.tesseraql.core.files.ColumnValues.zone(spec.timezone());
        List<ColumnMapping> columns = new ArrayList<>(spec.columns());
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            ColumnMapping.deriveIfAbsent(columns, row);
            if (printer.getRecordCount() == 0) {
                printer.printRecord(columns.stream().map(ColumnMapping::effectiveHeader).toList());
            }
            List<Object> cells = new ArrayList<>(columns.size());
            for (ColumnMapping column : columns) {
                cells.add(io.tesseraql.core.files.ColumnValues.format(
                        column, row.get(column.name()), locale, zone));
            }
            printer.printRecord(cells);
        }
        printer.flush();
    }
}
