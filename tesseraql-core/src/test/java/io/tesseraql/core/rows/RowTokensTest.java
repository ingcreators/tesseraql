package io.tesseraql.core.rows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The opaque row token over a declared key (docs/list-surface.md decision 2). */
class RowTokensTest {

    @Test
    void aSingleColumnKeyRoundTrips() {
        String token = RowTokens.encode(Map.of("id", 7), List.of("id"));
        assertThat(token).doesNotContain(".");
        assertThat(RowTokens.decode(token, List.of("id"))).containsExactly("7");
    }

    @Test
    void aCompositeKeyRoundTripsInDeclarationOrder() {
        String token = RowTokens.encode(Map.of("order_id", "A-1", "line_no", 2),
                List.of("order_id", "line_no"));
        assertThat(RowTokens.decode(token, List.of("order_id", "line_no")))
                .containsExactly("A-1", "2");
    }

    @Test
    void valuesWithDelimitersAndUnicodeRoundTrip() {
        // The join character, URL metacharacters, and Japanese identifiers
        // (docs/unicode-identifiers) — none of them may leak into the token structure.
        Map<String, Object> row = Map.of("a", "x.y/z?&#", "b", "山田商事");
        String token = RowTokens.encode(row, List.of("a", "b"));
        assertThat(token).matches("[A-Za-z0-9_\\-.]+");
        assertThat(RowTokens.decode(token, List.of("a", "b")))
                .containsExactly("x.y/z?&#", "山田商事");
    }

    @Test
    void tokensAreLegalHtmlIdAndUrlFragmentCharacters() {
        String token = RowTokens.encode(Map.of("id", "hello world"), List.of("id"));
        assertThat(token).matches("[A-Za-z0-9_\\-.]+");
    }

    @Test
    void aNullKeyComponentIsRefused() {
        Map<String, Object> row = new HashMap<>();
        row.put("id", null);
        assertThatThrownBy(() -> RowTokens.encode(row, List.of("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
    }

    @Test
    void anAbsentKeyComponentIsRefused() {
        assertThatThrownBy(() -> RowTokens.encode(Map.of("name", "x"), List.of("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
    }

    @Test
    void aBlankKeyComponentIsRefused() {
        assertThatThrownBy(() -> RowTokens.encode(Map.of("id", ""), List.of("id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'id'");
    }

    @Test
    void anArityMismatchIsRefusedOnDecode() {
        String token = RowTokens.encode(Map.of("a", 1, "b", 2), List.of("a", "b"));
        assertThatThrownBy(() -> RowTokens.decode(token, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 value(s)");
    }

    @Test
    void aMalformedTokenIsRefusedOnDecode() {
        assertThatThrownBy(() -> RowTokens.decode("not|base64url", List.of("id")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RowTokens.decode("", List.of("id")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
