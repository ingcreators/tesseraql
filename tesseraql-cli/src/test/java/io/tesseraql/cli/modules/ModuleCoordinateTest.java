package io.tesseraql.cli.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ModuleCoordinateTest {

    @Test
    void parsesGroupArtifactWithBomManagedVersion() {
        ModuleCoordinate coordinate = ModuleCoordinate.parse("io.tesseraql:tesseraql-pdf");
        assertThat(coordinate.groupId()).isEqualTo("io.tesseraql");
        assertThat(coordinate.artifactId()).isEqualTo("tesseraql-pdf");
        assertThat(coordinate.hasVersion()).isFalse();
        assertThat(coordinate.ga()).isEqualTo("io.tesseraql:tesseraql-pdf");
        assertThat(coordinate.canonical()).isEqualTo("io.tesseraql:tesseraql-pdf");
    }

    @Test
    void parsesPinnedVersion() {
        ModuleCoordinate coordinate = ModuleCoordinate.parse("com.example:driver:1.2.3");
        assertThat(coordinate.hasVersion()).isTrue();
        assertThat(coordinate.version()).isEqualTo("1.2.3");
        assertThat(coordinate.canonical()).isEqualTo("com.example:driver:1.2.3");
    }

    @Test
    void rejectsMalformedCoordinates() {
        assertThatThrownBy(() -> ModuleCoordinate.parse("just-one"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleCoordinate.parse("a:b:c:d"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModuleCoordinate.parse("a::c"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
