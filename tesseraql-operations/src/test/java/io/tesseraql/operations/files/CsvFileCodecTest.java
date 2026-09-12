package io.tesseraql.operations.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.ExportModel;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import io.tesseraql.core.files.SplitExport;
import io.tesseraql.core.files.SpooledRows;
import io.tesseraql.core.spool.FileTempStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvFileCodecTest {

    private static final byte[] MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final CsvFileCodec codec = new CsvFileCodec();

    @TempDir
    Path dir;

    private List<Map<String, Object>> read(String csv, FileReadSpec spec) throws Exception {
        return read(csv.getBytes(StandardCharsets.UTF_8), spec);
    }

    private List<Map<String, Object>> read(byte[] bytes, FileReadSpec spec) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        codec.read(new ByteArrayInputStream(bytes), spec, (rowNumber, values) -> rows.add(values));
        return rows;
    }

    /** The bytes a spreadsheet writes for "CSV UTF-8": the mark, then the text. */
    private static byte[] marked(String csv, java.nio.charset.Charset charset, byte... mark) {
        byte[] text = csv.getBytes(charset);
        byte[] bytes = new byte[mark.length + text.length];
        System.arraycopy(mark, 0, bytes, 0, mark.length);
        System.arraycopy(text, 0, bytes, mark.length, text.length);
        return bytes;
    }

    @Test
    void localizedHeaderLabelsMapToParameterNames() throws Exception {
        List<Map<String, Object>> rows = read("商品名,数量\nalpha,1\n",
                new FileReadSpec(List.of(
                        new ColumnMapping("productName", "商品名", null),
                        new ColumnMapping("qty", "数量", null)), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("productName")).isEqualTo("alpha");
        assertThat(rows.get(0).get("qty")).isEqualTo("1");
    }

    @Test
    void explicitColumnPositionsWinAndStartRowSkipsTitles() throws Exception {
        String csv = "monthly upload\nname,note,qty\nalpha,x,7\n";
        List<Map<String, Object>> rows = read(csv,
                new FileReadSpec(List.of(
                        new ColumnMapping("name", null, null),
                        new ColumnMapping("qty", null, ColumnMapping.parseColumn("C"))),
                        true, null, 2));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("alpha");
        assertThat(rows.get(0).get("qty")).isEqualTo("7");
    }

    @Test
    void aDeclaredColumnAbsentFromTheHeaderFailsRatherThanReadingNulls() {
        // A supplier renaming a header (or `qty` simply missing) used to import a full file of
        // silent nulls for that column; now the transfer fails loudly (silent-tolerance O4).
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> read("name\nalpha\n",
                new FileReadSpec(List.of(
                        new ColumnMapping("name", null, null),
                        new ColumnMapping("qty", null, null)), true, null, 1)))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("qty");
    }

    @Test
    void columnsDerivedFromTheHeaderAreNeverFlaggedAsUnmatched() throws Exception {
        // With no declared columns, the header itself defines them — they always match.
        List<Map<String, Object>> rows = read("name,qty\nalpha,7\n",
                new FileReadSpec(List.of(), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("name")).isEqualTo("alpha");
    }

    /**
     * "CSV UTF-8" is the encoding a spreadsheet offers by name, and it writes a byte-order mark.
     * Read as raw UTF-8 the mark becomes U+FEFF on the first header cell, which no header label
     * matches and which {@code String.trim()} does not remove, so the transfer was refused.
     */
    @Test
    void aUtf8ByteOrderMarkDoesNotHideTheFirstDeclaredColumn() throws Exception {
        List<Map<String, Object>> rows = read(
                marked("sku,qty\nA-1,5\n", StandardCharsets.UTF_8,
                        (byte) 0xEF, (byte) 0xBB, (byte) 0xBF),
                new FileReadSpec(List.of(
                        new ColumnMapping("sku", null, null),
                        new ColumnMapping("qty", null, null)), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("sku")).isEqualTo("A-1");
        assertThat(rows.get(0).get("qty")).isEqualTo("5");
    }

    /**
     * The silent half, and the worse one. With columns derived from the header there is nothing to
     * refuse: the first column was simply named "﻿sku", so the rendered statement bound its
     * `sku` parameter to null and wrote a null first column for every row of the file.
     */
    @Test
    void aUtf8ByteOrderMarkDoesNotRenameTheFirstDerivedColumn() throws Exception {
        List<Map<String, Object>> rows = read(
                marked("sku,qty\nA-1,5\n", StandardCharsets.UTF_8,
                        (byte) 0xEF, (byte) 0xBB, (byte) 0xBF),
                new FileReadSpec(List.of(), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsOnlyKeys("sku", "qty");
        assertThat(rows.get(0).get("sku")).isEqualTo("A-1");
    }

    /**
     * A mark names its encoding, so it decides the charset rather than merely being skipped.
     * "Unicode Text" is the spreadsheet's other Unicode save, and it is UTF-16.
     */
    @Test
    void aUtf16MarkedFileIsReadInTheEncodingItsMarkNames() throws Exception {
        List<Map<String, Object>> rows = read(
                marked("sku,qty\nA-1,5\n", StandardCharsets.UTF_16LE,
                        (byte) 0xFF, (byte) 0xFE),
                new FileReadSpec(List.of(
                        new ColumnMapping("sku", null, null),
                        new ColumnMapping("qty", null, null)), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("sku")).isEqualTo("A-1");
        assertThat(rows.get(0).get("qty")).isEqualTo("5");
    }

    /** No mark still means UTF-8. There is no sniffing: a wrong guess writes wrong data. */
    @Test
    void anUnmarkedFileIsStillUtf8() throws Exception {
        List<Map<String, Object>> rows = read("商品,数量\nアルファ,3\n",
                new FileReadSpec(List.of(
                        new ColumnMapping("product", "商品", null),
                        new ColumnMapping("qty", "数量", null)), true, null, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("product")).isEqualTo("アルファ");
    }

    @Test
    void writeFormatsDatesAndNumbersWithTheTransferLocale() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("held_on", java.sql.Timestamp.from(
                java.time.Instant.parse("2026-06-10T23:30:00Z")));
        row.put("fee", new java.math.BigDecimal("1234.5"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("held_on", null, null, null, "yyyy/MM/dd HH:mm"),
                new ColumnMapping("fee", null, null, "number", "#,##0.00")),
                null, null, null, "de-DE", "Asia/Tokyo"),
                io.tesseraql.core.files.ExportModel.streaming(List.of(row).iterator(),
                        java.util.Map.of()));

        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("2026/06/11 08:30") // rendered in the transfer's time zone
                .contains("\"1.234,50\""); // German grouping/decimal separators
    }

    @Test
    void writeRendersASqlTimeAsWallClockText() throws Exception {
        // java.sql.Time is what pgjdbc, H2, MySQL, MariaDB and SQL Server hand for a TIME
        // column; its toInstant() throws, and the old toZoned asked. Never LocalTime here: DuckDB's
        // shape passed all along (MEASUREMENT.md section 8 hazard 24). The formatted column uses
        // a locale-sensitive field, so the export's locale is proven on the wire, not assumed.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", "late");
        row.put("starts_at", java.sql.Time.valueOf("22:30:00"));
        row.put("ends_at", java.sql.Time.valueOf("23:45:00"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                ColumnMapping.of("label"),
                ColumnMapping.of("starts_at"),
                new ColumnMapping("ends_at", null, null, null, "hh:mm a")),
                null, null, null, "ja-JP", "Asia/Tokyo"),
                io.tesseraql.core.files.ExportModel.streaming(List.of(row).iterator(),
                        Map.of()));

        assertThat(out.toString(StandardCharsets.UTF_8))
                // untyped: ISO wall-clock text; formatted: the pattern over the time, in ja-JP
                .contains("late,22:30:00,11:45 午後\r\n");
    }

    @Test
    void writeUsesHeaderLabelsAndColumnOrder() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qty", 5);
        row.put("productName", "alpha");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, new FileWriteSpec(List.of(
                new ColumnMapping("productName", "商品名", null),
                new ColumnMapping("qty", "数量", null)), null, null, null),
                io.tesseraql.core.files.ExportModel.streaming(List.of(row).iterator(),
                        java.util.Map.of()));

        // Byte 0 is the first header label (商 = E5 95 86): no mark unless one is declared. Pinned
        // on bytes, because a decoded String can hide a stray mark behind a character nobody prints.
        assertThat(out.toByteArray()).startsWith((byte) 0xE5, (byte) 0x95, (byte) 0x86);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("商品名,数量")
                .contains("alpha,5");
    }

    private static FileWriteSpec spec(List<ColumnMapping> columns, String locale, boolean bom) {
        return new FileWriteSpec(columns, null, null, null, null, locale, null, null, null, bom);
    }

    private static List<ColumnMapping> labelled() {
        return List.of(
                new ColumnMapping("productName", "商品名", null),
                new ColumnMapping("qty", "数量", null));
    }

    /** Enough rows to flush the writer's encoder more than once (about 20 KiB). */
    private static List<Map<String, Object>> wide() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("qty", i);
            row.put("productName", "商品-" + i);
            rows.add(row);
        }
        return rows;
    }

    private byte[] write(FileWriteSpec spec, List<Map<String, Object>> rows) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        codec.write(out, spec, ExportModel.streaming(rows.iterator(), Map.of()));
        return out.toByteArray();
    }

    /**
     * The marked-versus-plain comparison is whole-body equality over a fixture wider than the
     * encoder's 8 KiB buffer: a mark written late, twice, or per row lands somewhere inside those
     * bytes, and "starts with the mark" cannot see it.
     */
    @Test
    void writeOpensWithTheUtf8MarkWhenDeclaredAndOnlyThere() throws Exception {
        byte[] marked = write(spec(labelled(), null, true), wide());
        byte[] plain = write(spec(labelled(), null, false), wide());

        assertThat(plain.length).as("the fixture outruns the encoder buffer").isGreaterThan(16384);
        assertThat(marked).startsWith(MARK);
        // The mark is the whole difference: after it, the marked file is the plain one byte for
        // byte - so no second mark anywhere, no row shifted, no header cell renamed.
        assertThat(Arrays.copyOfRange(marked, 3, marked.length)).isEqualTo(plain);
    }

    /** The mark is never derived: a Japanese locale writes dates in Japanese, not a signature. */
    @Test
    void aJapaneseLocaleDoesNotImplyAMark() throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("qty", 5);
        row.put("productName", "alpha");
        byte[] bytes = write(spec(labelled(), "ja", false), List.of(row));

        assertThat(bytes).startsWith((byte) 0xE5, (byte) 0x95, (byte) 0x86);
    }

    /**
     * The mark is written before anything asks whether a row exists, so an empty export with
     * {@code bom: true} is exactly the mark. No columns are declared on purpose: a future header
     * row for an empty export with declared columns would follow the mark, and this fixture stays
     * exact either way.
     */
    @Test
    void anEmptyExportWithTheMarkIsExactlyTheMark() throws Exception {
        byte[] bytes = write(spec(List.of(), null, true), List.of());

        assertThat(bytes).containsExactly(MARK);
    }

    /**
     * {@code SplitExport.write} calls the codec once per ZIP entry, so a marked split export
     * carries one mark inside every entry - once, at byte 0 - and none in front of the archive.
     */
    @Test
    void eachSplitDocumentOpensWithItsOwnMark() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("sales", "ann"));
        rows.add(row("sales", "bob"));
        rows.add(row("ops", "cat"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (SpooledRows spooled = SpooledRows.drain(new FileTempStore(dir.resolve("spool")),
                rows.iterator())) {
            SplitExport.write(codec, spec(List.of(ColumnMapping.of("name")), null, true), spooled,
                    Map.of(), "dept", "team-{key}.csv", out);
        }
        byte[] zip = out.toByteArray();

        assertThat(zip).startsWith((byte) 0x50, (byte) 0x4B); // the archive itself is unmarked
        Map<String, byte[]> entries = entries(zip);
        assertThat(entries).containsOnlyKeys("team-sales.csv", "team-ops.csv");
        assertThat(entries.get("team-sales.csv"))
                .isEqualTo(concat(MARK, "name\r\nann\r\nbob\r\n".getBytes(StandardCharsets.UTF_8)));
        assertThat(entries.get("team-ops.csv"))
                .isEqualTo(concat(MARK, "name\r\ncat\r\n".getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static Map<String, Object> row(String dept, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dept", dept);
        row.put("name", name);
        return row;
    }

    private static Map<String, byte[]> entries(byte[] zip) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries.put(entry.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
