package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.TempStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The substrate for everything re-readable (docs/export-pipeline.md, decision 8). The property
 * that matters is not that rows survive a round trip but that their <em>types</em> do: a report
 * whose rows went through a lossy encoding renders a numeric cell as text and loses a date cell's
 * format, which is a changed document, not a changed memory profile.
 */
class SpooledRowsTest {

    @TempDir
    Path dir;

    private TempStore store() {
        return new FileTempStore(dir.resolve("spool"));
    }

    @Test
    void everySupportedTypeSurvivesTheRoundTripAsItself() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("text", "アルファ");
        row.put("flag", Boolean.TRUE);
        row.put("count", 42);
        row.put("big", 9_000_000_000L);
        row.put("small", (short) 7);
        row.put("tiny", (byte) 3);
        row.put("rate", 1.5d);
        row.put("ratio", 0.25f);
        // Scale is part of a money value: 1234.50 is not 1234.5 on a printed document.
        row.put("amount", new BigDecimal("1234.50"));
        row.put("day", LocalDate.of(2026, 8, 10));
        row.put("clock", LocalTime.of(13, 45, 30));
        row.put("moment", LocalDateTime.of(2026, 8, 10, 13, 45, 30));
        row.put("instant", Instant.parse("2026-08-10T04:45:30Z"));
        row.put("offset", OffsetDateTime.parse("2026-08-10T13:45:30+09:00"));
        row.put("sqlDay", java.sql.Date.valueOf("2026-08-10"));
        row.put("sqlClock", java.sql.Time.valueOf("13:45:30"));
        row.put("blob", new byte[]{1, 2, 3});
        row.put("nothing", null);
        java.sql.Timestamp stamp = java.sql.Timestamp.valueOf("2026-08-10 13:45:30");
        stamp.setNanos(123_456_789);
        row.put("stamped", stamp);

        SpooledRows spooled = SpooledRows.drain(store(), List.of(row).iterator());

        Map<String, Object> read = spooled.iterator().next();
        assertThat(read.keySet()).containsExactlyElementsOf(row.keySet());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            Object actual = read.get(entry.getKey());
            if (entry.getValue() instanceof byte[] expected) {
                assertThat(actual).isInstanceOf(byte[].class);
                assertThat((byte[]) actual).containsExactly(expected);
                continue;
            }
            assertThat(actual)
                    .as("column %s", entry.getKey())
                    .isEqualTo(entry.getValue());
            if (entry.getValue() != null) {
                assertThat(actual).hasSameClassAs(entry.getValue());
            }
        }
        assertThat(((java.sql.Timestamp) read.get("stamped")).getNanos()).isEqualTo(123_456_789);
        spooled.close();
    }

    @Test
    void theRowsCanBeWalkedMoreThanOnce() {
        SpooledRows spooled = SpooledRows.drain(store(), rows(3).iterator());

        assertThat(names(spooled)).containsExactly("r0", "r1", "r2");
        assertThat(names(spooled)).containsExactly("r0", "r1", "r2");
        assertThat(spooled.size()).isEqualTo(3);
        assertThat(spooled.columns()).containsExactly("name");
        spooled.close();
    }

    @Test
    void anEmptySourceIsAnEmptySequence() {
        SpooledRows spooled = SpooledRows.drain(store(),
                List.<Map<String, Object>>of().iterator());

        assertThat(spooled.iterator().hasNext()).isFalse();
        assertThat(spooled.size()).isZero();
        spooled.close();
    }

    @Test
    void aValueTheEncodingCannotCarryFailsWithItsColumnNamed() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("weird", new java.util.concurrent.atomic.AtomicInteger(1));

        assertThatThrownBy(() -> SpooledRows.drain(store(), List.of(row).iterator()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("weird")
                .hasMessageContaining("AtomicInteger")
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2853");
    }

    @Test
    void aRowThatChangesShapeFailsRatherThanLosingColumns() {
        List<Map<String, Object>> rows = new ArrayList<>(rows(1));
        rows.add(new LinkedHashMap<>(Map.of("other", "x")));

        assertThatThrownBy(() -> SpooledRows.drain(store(), rows.iterator()))
                .isInstanceOf(TqlException.class)
                .extracting(error -> ((TqlException) error).code().toString())
                .isEqualTo("TQL-LD-2854");
    }

    private static List<Map<String, Object>> rows(int count) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", "r" + i);
            rows.add(row);
        }
        return rows;
    }

    private static List<String> names(SpooledRows spooled) {
        List<String> names = new ArrayList<>();
        spooled.forEach(row -> names.add((String) row.get("name")));
        return names;
    }
}
