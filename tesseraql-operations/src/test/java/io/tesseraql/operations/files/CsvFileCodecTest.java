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
    void missingHeaderLabelYieldsNullInsteadOfFailing() throws Exception {
        List<Map<String, Object>> rows = read("name\nalpha\n",
                new FileReadSpec(List.of(
                        new ColumnMapping("name", null, null),
                        new ColumnMapping("qty", null, null)), true, null, 1));
        assertThat(rows.get(0).get("qty")).isNull();
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
                List.of(row).iterator());

        assertThat(out.toString(StandardCharsets.UTF_8))
                .startsWith("商品名,数量")
                .contains("alpha,5");
    }
}
