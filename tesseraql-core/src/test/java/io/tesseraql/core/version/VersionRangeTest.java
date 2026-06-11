package io.tesseraql.core.version;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class VersionRangeTest {

    @Test
    void parsesAndOrdersVersions() {
        assertThat(SemanticVersion.parse("1")).isEqualTo(new SemanticVersion(1, 0, 0));
        assertThat(SemanticVersion.parse("1.2")).isEqualTo(new SemanticVersion(1, 2, 0));
        assertThat(SemanticVersion.parse("1.2.3-rc1+build")).isEqualTo(new SemanticVersion(1, 2, 3));
        assertThat(SemanticVersion.parse("1.2.3")).isGreaterThan(SemanticVersion.parse("1.2.0"));
        assertThat(SemanticVersion.parse("2.0.0")).isGreaterThan(SemanticVersion.parse("1.9.9"));
    }

    @Test
    void rejectsInvalidVersions() {
        assertThatThrownBy(() -> SemanticVersion.parse("abc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openRangeIncludesEverything() {
        assertThat(VersionRange.parse("*").includes(SemanticVersion.parse("9.9.9"))).isTrue();
        assertThat(VersionRange.parse("").includes(SemanticVersion.parse("0.0.1"))).isTrue();
    }

    @Test
    void boundedRangeChecksConstraints() {
        VersionRange range = VersionRange.parse(">=0.1.0, <0.2.0");
        assertThat(range.includes(SemanticVersion.parse("0.1.5"))).isTrue();
        assertThat(range.includes(SemanticVersion.parse("0.2.0"))).isFalse();
        assertThat(range.includes(SemanticVersion.parse("0.0.9"))).isFalse();
    }

    @Test
    void exactConstraint() {
        VersionRange range = VersionRange.parse("1.2.0");
        assertThat(range.includes(SemanticVersion.parse("1.2.0"))).isTrue();
        assertThat(range.includes(SemanticVersion.parse("1.2.1"))).isFalse();
    }
}
