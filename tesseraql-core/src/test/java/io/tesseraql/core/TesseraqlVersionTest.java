package io.tesseraql.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TesseraqlVersionTest {

    @Test
    void resolvesTheBuildVersionFromTheFilteredResource() {
        String version = TesseraqlVersion.current();
        assertThat(version).isNotBlank().isNotEqualTo("unknown")
                .doesNotContain("${")
                .matches("\\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
    }
}
