package io.tesseraql.core.rows;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Canonical join keys (docs/lookups.md, decision 7). */
class JoinKeysTest {

    @Test
    void aSingleColumnKeyIsTheCanonicalValueItself() {
        assertThat(JoinKeys.of(row("id", 1), List.of("id")))
                .isEqualTo(JoinKeys.of(row("id", 1L), List.of("id")));
    }

    @Test
    void aCompositeKeyComparesEveryColumn() {
        Map<String, Object> left = row("a", 1, "b", "x");
        assertThat(JoinKeys.of(left, List.of("a", "b")))
                .isEqualTo(JoinKeys.of(row("a", 1L, "b", "x"), List.of("a", "b")))
                .isNotEqualTo(JoinKeys.of(row("a", 1, "b", "y"), List.of("a", "b")));
    }

    @Test
    void columnOrderIsPartOfTheKey() {
        Map<String, Object> row = row("a", 1, "b", 2);
        assertThat(JoinKeys.of(row, List.of("a", "b")))
                .isNotEqualTo(JoinKeys.of(row, List.of("b", "a")));
    }

    @Test
    void aNullKeyColumnStaysNullRatherThanBecomingTheTextNull() {
        assertThat(JoinKeys.of(row("id", null), List.of("id"))).isNull();
        assertThat(JoinKeys.of(row("a", null, "b", 1), List.of("a", "b")))
                .isEqualTo(java.util.Arrays.asList(null, "1"))
                .isNotEqualTo(JoinKeys.of(row("a", "null", "b", 1), List.of("a", "b")));
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }
}
