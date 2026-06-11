package io.tesseraql.operations.files;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
            Iterator<CSVRecord> records = parser.iterator();
            for (int skip = 1; skip < spec.startRow() && records.hasNext(); skip++) {
                records.next();
            }
            List<String> header = null;
            if (spec.headerRow() && records.hasNext()) {
                header = new ArrayList<>();
                for (String cell : records.next()) {
                    header.add(cell);
                }
            }
            List<ColumnMapping> columns = spec.columns().isEmpty() && header != null
                    ? header.stream().map(ColumnMapping::of).toList()
                    : spec.columns();
            int[] positions = io.tesseraql.core.files.Tables.positions(columns, header);
            long rowNumber = 0;
            while (records.hasNext()) {
                CSVRecord record = records.next();
                rowNumber++;
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    int position = positions[i];
                    values.put(columns.get(i).name(),
                            position >= 0 && position < record.size()
                                    ? record.get(position)
                                    : null);
                }
                handler.row(rowNumber, values);
            }
        }
    }

    @Override
    // The printer is deliberately not closed: closing it would close the caller-owned stream;
    // the codec contract is flush-only.
    @SuppressWarnings("resource")
    public void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException {
        CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.RFC4180);
        java.util.Locale locale = io.tesseraql.core.files.ColumnValues.locale(spec.locale());
        java.time.ZoneId zone = io.tesseraql.core.files.ColumnValues.zone(spec.timezone());
        List<ColumnMapping> columns = new ArrayList<>(spec.columns());
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            if (columns.isEmpty()) {
                row.keySet().forEach(key -> columns.add(ColumnMapping.of(key)));
            }
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
