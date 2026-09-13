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

    /** A codec that prints the document's named values, so narrowing is visible in the entry. */
    private static final FileCodec HEADER_CODEC = new FileCodec() {

        @Override
        public String format() {
            return "header-text";
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
            for (Map.Entry<String, Object> value : model.values().entrySet()) {
                Object result = value.getValue();
                Object first = result instanceof Map<?, ?> map ? map.get("first") : null;
                out.write((value.getKey() + "=" + first + "\n").getBytes(StandardCharsets.UTF_8));
            }
            for (Map<String, Object> row : model.repeatableRows()) {
                out.write((row.get("name") + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    };

    private static Map<String, Object> headerRow(String dept, String customer) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dept", dept);
        row.put("customer", customer);
        return row;
    }

    private static Map<String, Object> companyRow(String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("company", name);
        return row;
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
    void eachDocumentReadsItsOwnNamedResultAndSharesTheRest() throws Exception {
        // The flagship case: five hundred invoices split by customer printed the same customer,
        // and the only way out was denormalizing the header onto every line — the pattern named
        // queries exist to end (docs/export-pipeline.md, decision 16).
        SpooledRows customers = spool(List.of(
                headerRow("sales", "Acme"), headerRow("ops", "Globex")));
        SpooledRows company = spool(List.of(companyRow("TesseraQL KK")));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("customer", ExportModel.result(customers, 2));
        values.put("company", ExportModel.result(company, 1));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SplitExport.write(HEADER_CODEC, spec(),
                spool(List.of(row("sales", "ann"), row("ops", "cat"))), values, "dept",
                "team-{key}.txt", out);

        Map<String, String> entries = entries(out.toByteArray());
        // The customer query selects the split column, so each document reads its own row.
        assertThat(entries.get("team-sales.txt")).contains("Acme").doesNotContain("Globex");
        assertThat(entries.get("team-ops.txt")).contains("Globex").doesNotContain("Acme");
        // The company query does not, so every document reads the same one.
        assertThat(entries.get("team-sales.txt")).contains("TesseraQL KK");
        assertThat(entries.get("team-ops.txt")).contains("TesseraQL KK");
    }

    @Test
    void anUnorderedNarrowableResultFailsNamingTheQuery() {
        SpooledRows customers = spool(List.of(
                headerRow("sales", "Acme"), headerRow("ops", "Globex"),
                headerRow("sales", "Acme again")));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("customer", ExportModel.result(customers, 3));

        assertThatThrownBy(() -> SplitExport.write(HEADER_CODEC, spec(),
                spool(List.of(row("sales", "ann"), row("ops", "cat"))), values, "dept",
                "team-{key}.txt", new ByteArrayOutputStream()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Named query 'customer'")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2851");
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

    /**
     * The bound cuts on a code-point boundary and keeps the key's case (docs/export-hygiene.md
     * P1). A cut at unit 100 through a surrogate pair left a lone surrogate the ZIP encoder
     * refuses ("malformed input"), and the over-length branch alone lower-cased — so {@code A}×101
     * collided with {@code a}×101 while {@code A}×100 and {@code a}×100 did not.
     */
    @Test
    void aLongKeyIsCutOnACodePointBoundaryAndKeepsItsCase() {
        String odd = "X" + "𠮷".repeat(50); // 101 UTF-16 units; unit 100 is a LOW surrogate
        String cut = SplitExport.safe(odd);
        assertThat(cut).hasSize(99).startsWith("X𠮷");
        assertThat(Character.isHighSurrogate(cut.charAt(cut.length() - 1))).isFalse();
        assertThat(SplitExport.safe("A".repeat(101))).isEqualTo("A".repeat(100));
    }

    @Test
    void twoLongKeysDifferingInCaseAreTwoDocuments() throws Exception {
        String upper = "A".repeat(101);
        String lower = "a".repeat(101);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long groups = SplitExport.write(CODEC, spec(),
                spool(List.of(row(upper, "ann"), row(lower, "bob"))),
                Map.of(), "dept", "team-{key}.txt", out);
        assertThat(groups).isEqualTo(2);
        assertThat(entries(out.toByteArray())).containsKeys(
                "team-" + "A".repeat(100) + ".txt", "team-" + "a".repeat(100) + ".txt");
    }

    @Test
    void aKeyEndingInAnAstralLetterPastTheBoundIsWritten() throws Exception {
        String odd = "X" + "𠮷".repeat(50);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SplitExport.write(CODEC, spec(), spool(List.of(row(odd, "ann"))), Map.of(), "dept",
                "team-{key}.txt", out);
        assertThat(entries(out.toByteArray()).keySet()).singleElement().asString()
                .startsWith("team-X𠮷").endsWith("𠮷.txt");
    }

    /**
     * Every entry carries the extended-timestamp extra field, in the local header and in the
     * central directory, stamped 1980-01-01T00:00:00Z. Info-ZIP {@code unzip} 6.00 reads the
     * UTF-8 flag only from an entry that carries SOME extra field, so a bundle whose entries had
     * none unpacked a Japanese name as garbage on stock Debian and Ubuntu; a fixed stamp also
     * makes two identical exports byte-identical. The bytes are asserted, never an extracted
     * name: {@code java.util.zip} reads the name correctly with or without the field.
     */
    @Test
    void everyEntryCarriesTheExtendedTimestampInBothRecords(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SplitExport.write(CODEC, spec(), spool(List.of(row("東京", "ann"), row("大阪", "bob"))),
                Map.of(), "dept", "受注-{key}.txt", out);
        byte[] bundle = out.toByteArray();
        java.nio.file.attribute.FileTime epoch = java.nio.file.attribute.FileTime.from(
                java.time.Instant.parse("1980-01-01T00:00:00Z"));

        // The local headers, as a streaming reader sees them.
        int local = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                assertThat(extendedTimestamp(entry.getExtra())).as("local header of " + entry)
                        .isTrue();
                assertThat(entry.getLastModifiedTime()).as("stamp of " + entry).isEqualTo(epoch);
                local++;
            }
        }
        assertThat(local).isEqualTo(2);

        // The central directory, as unzip's listing and a random-access reader see it.
        Path file = dir.resolve("bundle.zip");
        java.nio.file.Files.write(file, bundle);
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(file.toFile())) {
            assertThat(zip.size()).isEqualTo(2);
            zip.stream().forEach(entry -> {
                assertThat(extendedTimestamp(entry.getExtra())).as("central directory of " + entry)
                        .isTrue();
                assertThat(entry.getLastModifiedTime()).as("stamp of " + entry).isEqualTo(epoch);
            });
        }
    }

    /** Whether an extra field starts with the extended-timestamp header id (0x5455, little-endian). */
    private static boolean extendedTimestamp(byte[] extra) {
        return extra != null && extra.length >= 4 && (extra[0] & 0xff) == 0x55
                && (extra[1] & 0xff) == 0x54;
    }
}
