package io.tesseraql.yaml.enrich;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.EnrichSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code source:} reference arm (docs/unified-sources.md decision 6): composing a sibling
 * source's rows, which is what {@code response.json.nest} was.
 *
 * <p>These are the join semantics that used to live in the JSON renderer's own implementation —
 * composite keys, canonical key comparison across driver types, and the many-to-one refusal.
 * They are asserted here because there is now one implementation of them, and it is this one:
 * the same code path a fetched reference takes, minus the fetch.
 */
class SiblingCompositionTest {

    private static final KeyedReference.Environment NO_FETCH = new KeyedReference.Environment() {

        @Override
        public java.sql.Connection connection(String datasource) {
            throw new AssertionError("a sibling source is already fetched; nothing may connect");
        }

        @Override
        public io.tesseraql.core.sql.ScopeResolver scopeResolver() {
            return io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
        }

        @Override
        public io.tesseraql.yaml.http.OutboundGateway gateway() {
            throw new AssertionError("a sibling source is already fetched; nothing may call out");
        }

        @Override
        public void degraded(String enrichment) {
            throw new AssertionError("a composition cannot fail its way into degrading");
        }
    };

    private static KeyedReference reference(EnrichSpec spec) {
        return new KeyedReference("partner", spec, List.of(), null, null, null,
                KeyedReference.Bounds.none(), (body, select) -> List.of());
    }

    private static EnrichSpec composing(Map<String, String> on, String as, List<String> merge) {
        return new EnrichSpec(on, null, null, "partners", null, as, merge, null, null);
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> result(List<Map<String, Object>> rows) {
        return Map.of("rows", rows);
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachesTheMatchingSiblingRowsAsAList() throws Exception {
        List<Map<String, Object>> enriched = reference(composing(Map.of("id", "order_id"),
                "lines", null))
                .enrich(NO_FETCH, Map.of("partners", result(List.of(
                        row("order_id", 1, "sku", "A"),
                        row("order_id", 1, "sku", "B"),
                        row("order_id", 2, "sku", "C")))),
                        List.of(row("id", 1), row("id", 2)));

        assertThat((List<Map<String, Object>>) enriched.get(0).get("lines")).hasSize(2);
        assertThat((List<Map<String, Object>>) enriched.get(1).get("lines")).hasSize(1);
    }

    @Test
    void mergesOnlyTheNamedColumnsAndKeepsAnUnmatchedRowsShape() throws Exception {
        List<Map<String, Object>> enriched = reference(composing(Map.of("partner_code", "code"),
                null, List.of("partner_name")))
                .enrich(NO_FETCH, Map.of("partners", result(List.of(
                        row("code", "P1", "partner_name", "Acme", "unused", "x")))),
                        List.of(row("id", 1, "partner_code", "P1"),
                                row("id", 2, "partner_code", "P9")));

        assertThat(enriched.get(0)).containsEntry("partner_name", "Acme")
                .doesNotContainKey("unused");
        // An unmatched row keeps the column, present and null: one row shape, not two.
        assertThat(enriched.get(1)).containsEntry("partner_name", null);
    }

    @Test
    void aCompositeKeyJoinsOnEveryColumn() throws Exception {
        List<Map<String, Object>> enriched = reference(composing(
                new LinkedHashMap<>(Map.of("buyer_code", "buyer", "supplier_code", "supplier")),
                null, List.of("partner_name")))
                .enrich(NO_FETCH, Map.of("partners", result(List.of(
                        row("buyer", "B1", "supplier", "S1", "partner_name", "first"),
                        row("buyer", "B1", "supplier", "S2", "partner_name", "second")))),
                        List.of(row("buyer_code", "B1", "supplier_code", "S1"),
                                row("buyer_code", "B1", "supplier_code", "S2")));

        // A single-column join would have matched both rows to the first reference row.
        assertThat(enriched.get(0)).containsEntry("partner_name", "first");
        assertThat(enriched.get(1)).containsEntry("partner_name", "second");
    }

    @Test
    void keysCompareCanonicallyAcrossDriverTypes() throws Exception {
        List<Map<String, Object>> enriched = reference(composing(Map.of("id", "id"),
                null, List.of("partner_name")))
                .enrich(NO_FETCH,
                        Map.of("partners", result(List.of(row("id", 1L, "partner_name", "Acme")))),
                        List.of(row("id", 1)));

        assertThat(enriched.get(0)).containsEntry("partner_name", "Acme");
    }

    @Test
    void aMergeMatchingSeveralRowsFailsRatherThanPickingOne() {
        KeyedReference composition = reference(composing(Map.of("partner_code", "code"), null,
                List.of("partner_name")));
        Map<String, Object> context = Map.of("partners", result(List.of(
                row("code", "P1", "partner_name", "one"),
                row("code", "P1", "partner_name", "two"))));

        assertThatThrownBy(() -> composition.enrich(NO_FETCH, context,
                List.of(row("partner_code", "P1"))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("many-to-one");
    }

    @Test
    void aSourceNamingNoResultSetIsRefused() {
        KeyedReference composition = reference(composing(Map.of("partner_code", "code"), "p",
                null));

        assertThatThrownBy(() -> composition.enrich(NO_FETCH, Map.of(),
                List.of(row("partner_code", "P1"))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("is not a result set with rows");
    }
}
