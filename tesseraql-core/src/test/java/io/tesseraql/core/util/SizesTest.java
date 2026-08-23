package io.tesseraql.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import org.junit.jupiter.api.Test;

class SizesTest {

    @Test
    void parsesUnitsAndBareBytes() {
        assertThat(Sizes.parseBytes("1048576")).isEqualTo(1_048_576L);
        assertThat(Sizes.parseBytes("512B")).isEqualTo(512L);
        assertThat(Sizes.parseBytes("512KB")).isEqualTo(512L * 1024);
        assertThat(Sizes.parseBytes("25MB")).isEqualTo(25L * 1024 * 1024);
        assertThat(Sizes.parseBytes("2GB")).isEqualTo(2L * 1024 * 1024 * 1024);
        assertThat(Sizes.parseBytes(" 25mb ")).isEqualTo(25L * 1024 * 1024);
        assertThat(Sizes.parseBytes("0")).isZero();
    }

    @Test
    void rejectsInvalid() {
        assertThatThrownBy(() -> Sizes.parseBytes("abc")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Sizes.parseBytes("10TB")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Sizes.parseBytes("-1")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Sizes.parseBytes("")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Sizes.parseBytes(null)).isInstanceOf(TqlException.class);
    }

    /** A boot refusal must name the key to fix, not just the value that broke. */
    @Test
    void theSubjectNamesTheKeyInTheRefusal() {
        assertThatThrownBy(() -> Sizes.parseBytes("huge", "tesseraql.temp.maxBytes"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("tesseraql.temp.maxBytes")
                .hasMessageContaining("huge");
    }
}
