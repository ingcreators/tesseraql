package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.FileTempStore;
import io.tesseraql.core.spool.TempStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
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

    /** The spool files alive under the store — the sensitivity control for every leak guard. */
    private long spoolFiles() {
        try (var files = Files.list(dir.resolve("spool"))) {
            return files.count();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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
        // The control the leak guards below rest on: a completed drain holds exactly one spool
        // file until it is closed, and none after.
        assertThat(spoolFiles()).isEqualTo(1);
        spooled.close();
        assertThat(spoolFiles()).isZero();
    }

    /**
     * A drain that fails part-way leaves nothing behind (docs/export-hygiene.md P2). The
     * try-with-resources closed the writer — on a staging store that INSERTS the partial spool —
     * and then nothing held the reference: every failed buffered, split or multi-source export
     * left one orphan per run that no sweep could ever see.
     */
    @Test
    void aDrainThatFailsMidWayLeavesNoSpoolBehind() {
        // The source refuses after two rows — the row cap, a database error in hasNext().
        Iterator<Map<String, Object>> refusing = new Iterator<>() {
            private final Iterator<Map<String, Object>> rows = rows(2).iterator();

            @Override
            public boolean hasNext() {
                if (rows.hasNext()) {
                    return true;
                }
                throw new IllegalStateException("the source refused after two rows");
            }

            @Override
            public Map<String, Object> next() {
                return rows.next();
            }
        };
        assertThatThrownBy(() -> SpooledRows.drain(store(), refusing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(spoolFiles()).as("after a source failure").isZero();

        // The encoder refuses a value.
        Map<String, Object> weird = new LinkedHashMap<>();
        weird.put("weird", new java.util.concurrent.atomic.AtomicInteger(1));
        List<Map<String, Object>> unrepresentable = new ArrayList<>(rows(2));
        unrepresentable.add(weird);
        assertThatThrownBy(() -> SpooledRows.drain(store(), unrepresentable.iterator()))
                .isInstanceOf(TqlException.class);
        assertThat(spoolFiles()).as("after an unrepresentable value").isZero();

        // A row changes shape.
        List<Map<String, Object>> reshaped = new ArrayList<>(rows(2));
        reshaped.add(new LinkedHashMap<>(Map.of("other", "x")));
        assertThatThrownBy(() -> SpooledRows.drain(store(), reshaped.iterator()))
                .isInstanceOf(TqlException.class);
        assertThat(spoolFiles()).as("after a shape change").isZero();
    }

    /**
     * A zero-row drain keeps the column names its source knew (docs/export-hygiene.md P5): a
     * buffered export of an empty result then sees its columns, where the spool used to answer an
     * empty list and every codec printed no header.
     */
    @Test
    void aZeroRowDrainKeepsTheSourcesColumnNames() {
        SpooledRows spooled = SpooledRows.drain(store(), new NamedEmpty());
        assertThat(spooled.columns()).containsExactly("id", "name");
        assertThat(spooled.size()).isZero();
        assertThat(spooled.iterator().hasNext()).isFalse();
        spooled.close();
        assertThat(spoolFiles()).isZero();
    }

    /** A row source that knows its column names before any row, as a JDBC cursor does. */
    private static final class NamedEmpty
            implements
                Iterator<Map<String, Object>>,
                io.tesseraql.core.files.NamedRows {
        @Override
        public List<String> columns() {
            return List.of("id", "name");
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public Map<String, Object> next() {
            throw new java.util.NoSuchElementException();
        }
    }

    /**
     * A text past {@code writeUTF}'s ceiling — 65,535 bytes of modified UTF-8, which 21,846
     * Japanese characters reach — round-trips through the reader as itself. It used to fail every
     * buffered, split or multi-source export with {@code TQL-LD-2855}, naming no column.
     */
    @Test
    void aTextPastTheModifiedUtf8CeilingRoundTrips() {
        for (String text : List.of("a".repeat(65_535), "a".repeat(65_536), "い".repeat(21_846),
                "𠮷".repeat(10_923))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("note", text);
            SpooledRows spooled = SpooledRows.drain(store(), List.of(row).iterator());
            assertThat(spooled.iterator().next().get("note")).as(text.length() + " chars")
                    .isEqualTo(text);
            spooled.close();
        }
        assertThat(spoolFiles()).isZero();
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
