package io.tesseraql.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesUnits() {
        assertThat(Durations.toMillis("100ms")).isEqualTo(100);
        assertThat(Durations.toMillis("5s")).isEqualTo(5000);
        assertThat(Durations.toMillis("2m")).isEqualTo(120_000);
        assertThat(Durations.parse("8h").toHours()).isEqualTo(8);
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> Durations.parse("abc")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Durations.parse("10x")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Durations.parse("")).isInstanceOf(TqlException.class);
    }

    /** A boot refusal must name the key to fix, not just the value that broke. */
    @Test
    void theSubjectNamesTheKeyInTheRefusal() {
        assertThatThrownBy(() -> Durations.parse("PT30S", "stack.reconcile.interval"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("stack.reconcile.interval");
    }
}
