package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.FileTempStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Splitting by meaning is what a large printable document does instead of streaming
 * (docs/export-pipeline.md, decision 12). The output shape is a property of the route rather than
 * of today's data: one group still produces a ZIP, and so does none.
 */
class SplitExportTest {

    @TempDir
    Path dir;

    /** A codec that writes each row's name, so an entry's content is easy to assert on. */
    private static final FileCodec CODEC = new FileCodec() {

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
            for (Map<String, Object> row : model.repeatableRows()) {
                out.write((row.get("name") + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    };

    /** A streaming codec, to prove each entry follows its codec's own declaration. */
    private static final FileCodec STREAMING_CODEC = new FileCodec() {

        @Override
        public String format() {
            return "stream-text";
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
        public void write(OutputStream out, FileWriteSpec spec, ExportModel model)
                throws IOException {
            java.util.Iterator<Map<String, Object>> rows = model.rows();
            while (rows.hasNext()) {
                out.write((rows.next().get("name") + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    };

    private SpooledRows spool(List<Map<String, Object>> rows) {
        return SpooledRows.drain(new FileTempStore(dir.resolve("spool")), rows.iterator());
    }

    private static Map<String, Object> row(String dept, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dept", dept);
        row.put("name", name);
        return row;
    }

    private static Map<String, String> entries(byte[] zip) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    private static FileWriteSpec spec() {
        return new FileWriteSpec(List.of(), null, null, null);
    }

    @Test
    void eachGroupBecomesItsOwnDocumentInTheBundle() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long groups = SplitExport.write(CODEC, spec(),
                spool(List.of(row("sales", "ann"), row("sales", "bob"), row("ops", "cat"))),
                Map.of(), "dept", "team-{key}.txt", out);

        assertThat(groups).isEqualTo(2);
        assertThat(entries(out.toByteArray()))
                .containsEntry("team-sales.txt", "ann\nbob\n")
                .containsEntry("team-ops.txt", "cat\n");
    }

    @Test
    void aStreamingCodecGetsItsRowsOncePerDocument() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SplitExport.write(STREAMING_CODEC, spec(),
                spool(List.of(row("sales", "ann"), row("ops", "cat"))), Map.of(), "dept",
                "team-{key}.txt", out);

        assertThat(entries(out.toByteArray()))
                .containsEntry("team-sales.txt", "ann\n")
                .containsEntry("team-ops.txt", "cat\n");
    }

    @Test
    void oneGroupAndNoRowsBothProduceABundle() throws Exception {
        ByteArrayOutputStream one = new ByteArrayOutputStream();
        SplitExport.write(CODEC, spec(), spool(List.of(row("sales", "ann"))), Map.of(), "dept",
                "team-{key}.txt", one);
        assertThat(entries(one.toByteArray())).containsOnlyKeys("team-sales.txt");

        ByteArrayOutputStream none = new ByteArrayOutputStream();
        SplitExport.write(CODEC, spec(), spool(List.of()), Map.of(), "dept", "team-{key}.txt",
                none);
        assertThat(entries(none.toByteArray())).isEmpty();
        // Still a readable archive, not an empty file.
        assertThat(none.toByteArray()).isNotEmpty();
    }

    @Test
    void aFilenameWithoutTheKeyPlaceholderFails() {
        assertThatThrownBy(() -> SplitExport.write(CODEC, spec(),
                spool(List.of(row("sales", "ann"))), Map.of(), "dept", "team.txt",
                new ByteArrayOutputStream()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("{key}")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2858");
    }

    @Test
    void twoKeysThatNameTheSameFileFailRatherThanOverwrite() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("a/b", "ann"));
        rows.add(row("a_b", "bob"));

        assertThatThrownBy(() -> SplitExport.write(CODEC, spec(), spool(rows), Map.of(), "dept",
                "team-{key}.txt", new ByteArrayOutputStream()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("a/b")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2857");
    }

    @Test
    void aKeyIsMadeSafeForAFilesystem() {
        assertThat(SplitExport.safe("東京/支店")).isEqualTo("東京_支店");
        // A traversal attempt loses both its separator and its leading dots.
        assertThat(SplitExport.safe("../etc")).isEqualTo("__etc");
        assertThat(SplitExport.safe("")).isEqualTo("_");
    }
}
