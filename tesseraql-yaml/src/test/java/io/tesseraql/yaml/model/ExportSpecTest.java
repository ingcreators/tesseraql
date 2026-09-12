package io.tesseraql.yaml.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The YAML key is a nullable {@code Boolean}; the write spec is a primitive. {@code toWriteSpec}
 * is the one place the two meet, and the only place a declared {@code false} - or nothing -
 * becomes "no mark".
 */
class ExportSpecTest {

    private static ExportSpec spec(Boolean bom) {
        return new ExportSpec("csv", "items.csv", null, null, null, List.of(), null, null, null,
                null, null, null, null, bom);
    }

    @Test
    void aDeclaredMarkReachesTheWriteSpec() {
        assertThat(spec(Boolean.TRUE).toWriteSpec(null, null).bom()).isTrue();
    }

    @Test
    void anAbsentMarkIsOff() {
        assertThat(spec(null).toWriteSpec(null, null).bom()).isFalse();
    }

    @Test
    void aDeclinedMarkIsOff() {
        assertThat(spec(Boolean.FALSE).toWriteSpec(null, null).bom()).isFalse();
    }
}
