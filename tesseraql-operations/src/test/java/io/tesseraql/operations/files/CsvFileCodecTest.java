package io.tesseraql.operations.files;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.files.ColumnMapping;
import io.tesseraql.core.files.FileReadSpec;
import io.tesseraql.core.files.FileWriteSpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvFileCodecTest {

    private final CsvFileCodec codec = new CsvFileCodec();

    private List<Map<String, Object>> read(String csv, FileReadSpec spec) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        codec.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), spec,
                (rowNumber, values) -> rows.add(values));
        return rows;
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

        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("商品名,数量")
                .contains("alpha,5");
    }
}
