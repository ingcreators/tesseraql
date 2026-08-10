package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.FileTempStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Grouping belongs to the framework rather than the template (docs/export-pipeline.md, decision 3).
 * jxls can group with its own {@code groupBy}, but that materializes — {@code groupIterable}
 * returns a {@code Collection<GroupData>} whose items are a {@code Collection} — so the one route a
 * template had to {@code multisheet} was the one that buffers every row again.
 */
class ExportGroupsTest {

    @TempDir
    Path dir;

    private SpooledRows spool(List<Map<String, Object>> rows) {
        return SpooledRows.drain(new FileTempStore(dir.resolve("spool")), rows.iterator());
    }

    private static Map<String, Object> row(String dept, String name) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dept", dept);
        row.put("name", name);
        return row;
    }

    @Test
    void orderedRowsBecomeGroupsInTheirOwnOrder() {
        ExportGroups groups = ExportGroups.of(spool(List.of(
                row("sales", "ann"), row("sales", "bob"), row("ops", "cat"))), "dept");

        assertThat(groups.size()).isEqualTo(2);
        assertThat(groups.keys()).containsExactly("sales", "ops");

        List<String> rendered = new ArrayList<>();
        for (ExportGroups.Group group : groups) {
            List<String> names = new ArrayList<>();
            group.rows().forEach(row -> names.add((String) row.get("name")));
            rendered.add(group.key() + "=" + String.join(",", names));
        }
        assertThat(rendered).containsExactly("sales=ann,bob", "ops=cat");
    }

    @Test
    void theGroupsCanBeWalkedMoreThanOnce() {
        ExportGroups groups = ExportGroups.of(spool(List.of(
                row("sales", "ann"), row("ops", "cat"))), "dept");

        assertThat(groups).hasSize(2);
        assertThat(groups).hasSize(2);
    }

    @Test
    void aKeyThatComesBackFailsRatherThanWritingOneGroupAsTwo() {
        assertThatThrownBy(() -> ExportGroups.of(spool(List.of(
                row("sales", "ann"), row("ops", "cat"), row("sales", "bob"))), "dept"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("order the extraction by dept")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2851");
    }

    @Test
    void aColumnTheExtractionDoesNotSelectFailsAtOnce() {
        assertThatThrownBy(() -> ExportGroups.of(spool(List.of(row("sales", "ann"))), "branch"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("does not select");
    }

    @Test
    void noRowsAreNoGroups() {
        assertThat(ExportGroups.of(spool(List.of()), "dept").size()).isZero();
    }
}
