package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.core.spool.TempStore;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every spool reader that is opened is also released. Three consumers used to abandon readers
 * mid-stream — the {@code first} peek of a result envelope, a group's early stop on ordered
 * rows, and the split export's per-document narrowing (which did both, per group) — and an
 * abandoned reader holds its stream until a walk that never comes. On the database-backed
 * {@code TempStore}, whose reads stage through a scratch file deleted on close, each abandoned
 * reader stranded a full on-disk copy of the spool.
 */
class SpoolReaderLeakTest {

    @TempDir
    Path dir;

    /** Counts the readers a consumer opens and the ones it releases. */
    private final class CountingStore implements TempStore {

        private final TempStore delegate = new FileTempStore(dir.resolve("spool"));
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public SpoolWriter createWriter(SpoolKind kind) {
            return delegate.createWriter(kind);
        }

        @Override
        public InputStream openInput(SpoolRef ref) throws IOException {
            opened.incrementAndGet();
            return new FilterInputStream(delegate.openInput(ref)) {
                private boolean counted;

                @Override
                public void close() throws IOException {
                    if (!counted) {
                        counted = true;
                        closed.incrementAndGet();
                    }
                    super.close();
                }
            };
        }

        @Override
        public void delete(SpoolRef ref) {
            delegate.delete(ref);
        }
    }

    private static Map<String, Object> row(Object group, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("grp", group);
        row.put("name", name);
        return row;
    }

    @Test
    void aResultAnswersItsFirstRowWithoutOpeningAReader() {
        CountingStore store = new CountingStore();
        SpooledRows spooled = SpooledRows.drain(store, List.of(
                row("a", "one"), row("a", "two")).iterator());

        Map<String, Object> result = ExportModel.result(spooled, spooled.size());

        assertThat(result.get("first")).isEqualTo(row("a", "one"));
        assertThat(result.get("rowCount")).isEqualTo(2L);
        assertThat(store.opened.get()).as("readers opened for the first-row peek").isZero();
    }

    @Test
    void anEmptySpoolAnswersANullFirstRow() {
        CountingStore store = new CountingStore();
        SpooledRows spooled = SpooledRows.drain(store, List.<Map<String, Object>>of().iterator());

        assertThat(spooled.firstRow()).isNull();
        assertThat(ExportModel.result(spooled, 0)).containsEntry("first", null);
    }

    @Test
    void groupedReadingClosesEveryReaderItOpens() {
        CountingStore store = new CountingStore();
        SpooledRows spooled = SpooledRows.drain(store, List.of(
                row("a", "one"), row("a", "two"), row("b", "three"), row("c", "four")).iterator());

        ExportGroups groups = ExportGroups.of(spooled, "grp");
        StringBuilder seen = new StringBuilder();
        for (ExportGroups.Group group : groups) {
            for (Map<String, Object> row : group.rows()) {
                seen.append(row.get("name")).append(' ');
            }
        }

        assertThat(seen.toString()).isEqualTo("one two three four ");
        // The boundary pass plus one read per group — and every one of them released. The
        // early-stopping reader of every group but the last used to stay open.
        assertThat(store.closed.get())
                .as("readers closed (opened %s)", store.opened.get())
                .isEqualTo(store.opened.get());
    }

    @Test
    void aSplitExportLeavesNoReaderOpen() throws Exception {
        CountingStore store = new CountingStore();
        SpooledRows extraction = SpooledRows.drain(store, List.of(
                row("a", "one"), row("a", "two"), row("b", "three")).iterator());
        // A narrowable named result: it selects the split column, so each document reads its
        // own rows from it — the site that used to abandon two readers per group.
        SpooledRows customers = SpooledRows.drain(store, List.of(
                row("a", "acme"), row("b", "burrows")).iterator());
        Map<String, Object> values = Map.of(
                "customer", ExportModel.result(customers, customers.size()));

        OutputStream out = new ByteArrayOutputStream();
        long written = SplitExport.write(textCodec(),
                new FileWriteSpec(List.of(), null, null, null), extraction,
                values, "grp", "doc-{key}.txt", out);

        assertThat(written).isEqualTo(2);
        assertThat(store.closed.get())
                .as("readers closed (opened %s)", store.opened.get())
                .isEqualTo(store.opened.get());
    }

    private static FileCodec textCodec() {
        return new FileCodec() {

            @Override
            public String format() {
                return "text";
            }

            @Override
            public String contentType() {
                return "text/plain";
            }

            @Override
            public String extension() {
                return ".txt";
            }

            @Override
            public void read(InputStream in, FileReadSpec spec, RowHandler handler) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean streams(FileWriteSpec spec) {
                return false;
            }

            @Override
            public void write(OutputStream out, FileWriteSpec spec, ExportModel model)
                    throws IOException {
                Object first = model.values().containsKey("customer")
                        ? ((Map<?, ?>) model.values().get("customer")).get("first")
                        : null;
                out.write((first + ":").getBytes(StandardCharsets.UTF_8));
                for (Map<String, Object> row : model.repeatableRows()) {
                    out.write((row.get("name") + " ").getBytes(StandardCharsets.UTF_8));
                }
            }
        };
    }
}
