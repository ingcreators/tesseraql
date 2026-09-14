package io.tesseraql.runtime;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileCodec;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.RowHandler;
import io.tesseraql.core.files.TabularReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A codec an application ships as a module (docs/codec-discovery.md): the class sits on the
 * test classpath, and a jar under {@code work/modules} carrying only its {@code META-INF/services}
 * line is what makes it a member of the application's codec set — the fixture shape
 * {@code MultiAppHostIntegrationTest.writeModuleJar} established for expression functions. The
 * document opens with the line {@code MARKER}, then a {@code |}-separated header and rows, so a
 * body proves which codec wrote it, and the counters prove which arm called it.
 */
public final class MarkerFileCodec implements FileCodec {

    static final String MARKER = "MARKER";

    static final AtomicInteger WRITES = new AtomicInteger();

    static final AtomicInteger READS = new AtomicInteger();

    @Override
    public String format() {
        return "marker";
    }

    @Override
    public String contentType() {
        return "text/x-marker; charset=utf-8";
    }

    @Override
    public String extension() {
        return ".marker";
    }

    @Override
    public void read(InputStream in, FileReadSpec spec, RowHandler handler) throws Exception {
        READS.incrementAndGet();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        if (!MARKER.equals(reader.readLine())) {
            throw new IllegalArgumentException("not a marker document");
        }
        Iterator<List<String>> rows = reader.lines().map(line -> List.of(line.split("\\|", -1)))
                .iterator();
        TabularReader.read(rows, spec, CELLS, handler);
    }

    private static final TabularReader.Cells<List<String>> CELLS = new TabularReader.Cells<List<String>>() {

        @Override
        public List<String> header(List<String> row) {
            return row;
        }

        @Override
        public Object value(List<String> row, int position, ColumnMapping column) {
            return position >= 0 && position < row.size() ? row.get(position) : null;
        }
    };

    @Override
    // The writer is flushed, never closed: the stream is the caller's (the codec contract).
    @SuppressWarnings("resource")
    public void write(OutputStream out, FileWriteSpec spec, ExportModel model)
            throws java.io.IOException {
        WRITES.incrementAndGet();
        Writer writer = new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8);
        writer.write(MARKER + "\n");
        List<ColumnMapping> columns = new ArrayList<>(spec.columns());
        ColumnMapping.deriveIfAbsent(columns, model.knownColumns());
        Iterator<Map<String, Object>> rows = model.rows();
        boolean headerWritten = false;
        while (rows.hasNext()) {
            Map<String, Object> row = rows.next();
            ColumnMapping.deriveIfAbsent(columns, row);
            if (!headerWritten) {
                writer.write(String.join("|", columns.stream().map(ColumnMapping::name).toList())
                        + "\n");
                headerWritten = true;
            }
            List<String> cells = new ArrayList<>();
            for (ColumnMapping column : columns) {
                cells.add(String.valueOf(row.get(column.name())));
            }
            writer.write(String.join("|", cells) + "\n");
        }
        writer.flush();
    }
}
