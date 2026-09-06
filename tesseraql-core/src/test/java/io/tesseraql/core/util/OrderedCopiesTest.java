package io.tesseraql.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OrderedCopiesTest {

    @Test
    void aCopyIteratesInTheOrderItsEntriesWereDeclared() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("betaCheckout", true);
        source.put("maxItems", 10);
        source.put("bannerText", "Hello");
        source.put("newSearch", false);
        source.put("exportCsv", true);
        source.put("darkMode", false);

        assertThat(OrderedCopies.map(source).keySet()).containsExactly("betaCheckout", "maxItems",
                "bannerText", "newSearch", "exportCsv", "darkMode");
        assertThat(OrderedCopies.mapAllowingNulls(source).keySet()).containsExactly("betaCheckout",
                "maxItems", "bannerText", "newSearch", "exportCsv", "darkMode");
    }

    @Test
    void aCopyDoesNotSeeLaterChangesToItsSource() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", 1);
        Map<String, Object> rejecting = OrderedCopies.map(source);
        Map<String, Object> permitting = OrderedCopies.mapAllowingNulls(source);

        source.put("b", 2);

        assertThat(rejecting).containsOnlyKeys("a");
        assertThat(permitting).containsOnlyKeys("a");
    }

    @Test
    void aCopyCannotBeModified() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("a", 1);

        assertThatThrownBy(() -> OrderedCopies.map(source).put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> OrderedCopies.mapAllowingNulls(source).put("b", 2))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theRejectingCopyNamesTheKeyWhoseValueIsNull() {
        // Map.copyOf's NullPointerException is the load-time guard this replaces, so the
        // replacement has to keep throwing it — it just says which key was at fault.
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("beta", true);
        source.put("maxItems", null);

        assertThatThrownBy(() -> OrderedCopies.map(source))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("maxItems");
    }

    @Test
    void theRejectingCopyRefusesANullKey() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put(null, 1);

        assertThatThrownBy(() -> OrderedCopies.map(source))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void thePermittingCopyKeepsANullValue() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("claim", null);

        assertThat(OrderedCopies.mapAllowingNulls(source)).containsEntry("claim", null);
    }

    @Test
    void neitherCopyAcceptsANullMap() {
        assertThatThrownBy(() -> OrderedCopies.map(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OrderedCopies.mapAllowingNulls(null))
                .isInstanceOf(NullPointerException.class);
    }
}
