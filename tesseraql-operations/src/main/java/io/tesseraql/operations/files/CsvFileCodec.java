package io.tesseraql.operations.files;

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
 * The built-in CSV codec (design ch. 28), RFC 4180 via Apache Commons CSV: UTF-8, quoted fields,
 * header row on both sides by default. On read the declared columns name the values (positional
 * without a header row, matched by header otherwise, the header names themselves when no columns
 * are declared); on write the declared columns order the output (the row's own order otherwise).
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
        CSVFormat format = spec.headerRow()
                ? CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).build()
                : CSVFormat.RFC4180;
        try (CSVParser parser = CSVParser.parse(
                new InputStreamReader(in, StandardCharsets.UTF_8), format)) {
            long rowNumber = 0;
            for (CSVRecord record : parser) {
                rowNumber++;
                handler.row(rowNumber, values(record, spec, parser));
            }
        }
    }

    private static Map<String, Object> values(CSVRecord record, FileReadSpec spec,
            CSVParser parser) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (!spec.columns().isEmpty()) {
            for (int i = 0; i < spec.columns().size(); i++) {
                String column = spec.columns().get(i);
                values.put(column, spec.headerRow()
                        ? (record.isMapped(column) ? record.get(column) : null)
                        : (i < record.size() ? record.get(i) : null));
            }
        } else {
            for (String header : parser.getHeaderNames()) {
                values.put(header, record.get(header));
            }
        }
        return values;
    }

    @Override
    public void write(OutputStream out, FileWriteSpec spec, Iterator<Map<String, Object>> rows)
            throws IOException {
        CSVPrinter printer = new CSVPrinter(
                new OutputStreamWriter(out, StandardCharsets.UTF_8), CSVFormat.RFC4180);
        List<String> columns = new ArrayList<>(spec.columns());
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            if (columns.isEmpty()) {
                columns.addAll(row.keySet());
            }
            if (printer.getRecordCount() == 0) {
                printer.printRecord(columns);
            }
            List<Object> cells = new ArrayList<>(columns.size());
            for (String column : columns) {
                cells.add(row.get(column));
            }
            printer.printRecord(cells);
        }
        printer.flush();
    }
}
