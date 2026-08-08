package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The pre-routing path decoder (docs/unicode-identifiers.md): non-ASCII percent-triplets
 * decode so Unicode route templates match; ASCII triplets stay encoded so an encoded slash
 * can never cross a segment boundary.
 */
class UnicodePathsTest {

    @Test
    void decodesOnlyNonAsciiTriplets() {
        assertThat(UnicodePaths.decodeNonAscii("/%E5%8F%97%E6%B3%A8/J-1002"))
                .isEqualTo("/受注/J-1002");
        assertThat(UnicodePaths.decodeNonAscii("/a%2Fb")).isEqualTo("/a%2Fb");
        assertThat(UnicodePaths.decodeNonAscii("/a%2e%2e/b")).isEqualTo("/a%2e%2e/b");
        assertThat(UnicodePaths.decodeNonAscii("/plain/path")).isEqualTo("/plain/path");
    }

    @Test
    void malformedSequencesPassThrough() {
        assertThat(UnicodePaths.decodeNonAscii("/x%G1")).isEqualTo("/x%G1");
        assertThat(UnicodePaths.decodeNonAscii("/x%")).isEqualTo("/x%");
        // A lone continuation byte decodes to the replacement character, never an exception.
        assertThat(UnicodePaths.decodeNonAscii("/x%E5")).startsWith("/x");
    }
}
