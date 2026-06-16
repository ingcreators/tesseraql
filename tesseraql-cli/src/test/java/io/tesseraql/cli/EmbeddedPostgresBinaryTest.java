package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddedPostgresBinaryTest {

    @Test
    void mapsTheSupportedPlatformsToZonkyClassifiers() {
        assertThat(EmbeddedPostgresBinary.classifier("Linux", "amd64")).isEqualTo("linux-amd64");
        assertThat(EmbeddedPostgresBinary.classifier("Linux", "x86_64")).isEqualTo("linux-amd64");
        assertThat(EmbeddedPostgresBinary.classifier("Linux", "aarch64"))
                .isEqualTo("linux-arm64v8");
        assertThat(EmbeddedPostgresBinary.classifier("Mac OS X", "x86_64"))
                .isEqualTo("darwin-amd64");
        assertThat(EmbeddedPostgresBinary.classifier("Mac OS X", "aarch64"))
                .isEqualTo("darwin-arm64v8");
        assertThat(EmbeddedPostgresBinary.classifier("Windows 11", "amd64"))
                .isEqualTo("windows-amd64");
    }

    @Test
    void rejectsPlatformsWithoutASupportedBinary() {
        assertThatThrownBy(() -> EmbeddedPostgresBinary.classifier("SunOS", "sparc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No embedded PostgreSQL binary");
        assertThatThrownBy(() -> EmbeddedPostgresBinary.classifier("Linux", "ppc64"))
                .isInstanceOf(IllegalStateException.class);
        // zonky ships no windows arm64 binary.
        assertThatThrownBy(() -> EmbeddedPostgresBinary.classifier("Windows 11", "aarch64"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("amd64 only");
    }
}
