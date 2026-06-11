package io.tesseraql.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ScimCountTest {

    @Test
    void readsNumericFirstColumn() {
        assertThat(ScimCount.toInt(Map.of("totalResults", 42L), -1)).isEqualTo(42);
    }

    @Test
    void parsesNumericString() {
        assertThat(ScimCount.toInt(Map.of("c", "7"), -1)).isEqualTo(7);
    }

    @Test
    void fallsBackOnAbsentOrNonNumeric() {
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("c", null);
        assertThat(ScimCount.toInt(null, 99)).isEqualTo(99);
        assertThat(ScimCount.toInt(Map.of(), 99)).isEqualTo(99);
        assertThat(ScimCount.toInt(nullValue, 99)).isEqualTo(99);
        assertThat(ScimCount.toInt(Map.of("c", "NaN"), 99)).isEqualTo(99);
    }
}
