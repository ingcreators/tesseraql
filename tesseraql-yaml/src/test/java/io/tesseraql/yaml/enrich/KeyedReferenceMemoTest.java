package io.tesseraql.yaml.enrich;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.EnrichSpec;
import io.tesseraql.yaml.model.HttpCallSpec;
import io.tesseraql.yaml.model.HttpSourceSpec;
import io.tesseraql.yaml.model.ResultCacheSpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The request memo and the per-key hold a reference consults before it fetches
 * (docs/caching.md decisions 8 and 9, docs/audit-low-leads.md F122): two blocks over one
 * master cost one lookup per distinct key, an absent key is remembered, a different reference
 * is its own memo, a held key serves across requests, and a degraded fetch remembers nothing.
 * The {@code perRow} HTTP arm is the fetch here because it is one call per key, which makes
 * "fetched once" a count of URLs.
 */
class KeyedReferenceMemoTest {

    private static final Map<String, String> ON = Map.of("partner_code", "code");
    private static final String URL = "http://partners.test/partners/{key.code}";

    private final List<String> calls = new ArrayList<>();
    private final List<String> degraded = new ArrayList<>();

    private final OutboundGateway gateway = new OutboundGateway() {
        @Override
        public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context) {
            calls.add(spec.url());
            String code = spec.url().substring(spec.url().lastIndexOf('/') + 1);
            List<Map<String, Object>> rows = code.startsWith("P9")
                    ? List.of()
                    : List.of(new LinkedHashMap<>(Map.of("name", "http-" + code)));
            return Map.of("body", rows);
        }

        @Override
        public Map<String, Object> call(HttpCallSpec spec, byte[] body,
                Map<String, String> headers) {
            throw new AssertionError("a perRow reference never posts a body");
        }

        @Override
        public OutboundGateway.RawResponse exchange(HttpCallSpec spec, byte[] body,
                Map<String, String> headers) {
            throw new AssertionError("a perRow reference never exchanges raw bytes");
        }
    };

    private static EnrichSpec perRow(String url, String onError, ResultCacheSpec cache) {
        HttpCallSpec call = new HttpCallSpec("GET", url, null, null, null, null, null, null,
                null, null);
        return new EnrichSpec(ON, null, new HttpSourceSpec(call, null, onError, null, null),
                null, EnrichSpec.PER_ROW, null, List.of("name"), null, null, cache);
    }

    @SuppressWarnings("unchecked")
    private static KeyedReference reference(String name, EnrichSpec spec) {
        return new KeyedReference(name, spec, List.of(), null, null, null,
                KeyedReference.Bounds.none(),
                (body, select) -> (List<Map<String, Object>>) body);
    }

    private KeyedReference.Environment environment(ReferenceMemo memo,
            KeyedReference.Hold hold, OutboundGateway through) {
        return new KeyedReference.Environment() {
            @Override
            public java.sql.Connection connection(String datasource) {
                throw new AssertionError("an http: reference never connects");
            }

            @Override
            public io.tesseraql.core.sql.ScopeResolver scopeResolver() {
                return io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
            }

            @Override
            public OutboundGateway gateway() {
                return through;
            }

            @Override
            public void degraded(String enrichment) {
                degraded.add(enrichment);
            }

            @Override
            public ReferenceMemo memo() {
                return memo;
            }

            @Override
            public KeyedReference.Hold hold() {
                return hold;
            }
        };
    }

    private static List<Map<String, Object>> rows(String... codes) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String code : codes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partner_code", code);
            rows.add(row);
        }
        return rows;
    }

    private static List<Object> names(List<Map<String, Object>> enriched) {
        return enriched.stream().map(row -> row.get("name")).toList();
    }

    @Test
    void twoBlocksOverOneMasterCostOneLookupPerDistinctKey() throws Exception {
        ReferenceMemo memo = new ReferenceMemo();
        KeyedReference.Environment environment = environment(memo, null, gateway);
        EnrichSpec spec = perRow(URL, null, null);

        List<Map<String, Object>> first = reference("orderPartner", spec)
                .enrich(environment, Map.of(), rows("P1", "P2", "P1"));
        assertThat(names(first)).containsExactly("http-P1", "http-P2", "http-P1");
        assertThat(calls).hasSize(2);

        // A second block, another instance of the same reference: P2 is remembered, P3 is new.
        List<Map<String, Object>> second = reference("historyPartner", spec)
                .enrich(environment, Map.of(), rows("P2", "P3"));
        assertThat(names(second)).containsExactly("http-P2", "http-P3");
        assertThat(calls).hasSize(3);
        assertThat(calls.get(2)).endsWith("/partners/P3");

        // A block whose keys are all known runs nothing at all.
        reference("third", spec).enrich(environment, Map.of(), rows("P1", "P3"));
        assertThat(calls).hasSize(3);
    }

    @Test
    void anAbsentKeyIsRememberedAndADifferentReferenceIsItsOwnMemo() throws Exception {
        ReferenceMemo memo = new ReferenceMemo();
        KeyedReference.Environment environment = environment(memo, null, gateway);
        EnrichSpec spec = perRow(URL, null, null);
        assertThat(names(reference("a", spec).enrich(environment, Map.of(), rows("P9"))))
                .containsExactly((Object) null);
        reference("b", spec).enrich(environment, Map.of(), rows("P9"));
        assertThat(calls).as("an absent key is asked once").hasSize(1);

        EnrichSpec other = perRow("http://partners.test/other/{key.code}", null, null);
        reference("c", other).enrich(environment, Map.of(), rows("P9"));
        assertThat(calls).as("another url is another reference").hasSize(2);
    }

    @Test
    void aSurfaceWithoutAMemoFetchesEveryBlock() throws Exception {
        KeyedReference.Environment environment = environment(null, null, gateway);
        EnrichSpec spec = perRow(URL, null, null);
        reference("a", spec).enrich(environment, Map.of(), rows("P1", "P2"));
        reference("b", spec).enrich(environment, Map.of(), rows("P2", "P3"));
        assertThat(calls).hasSize(4);
    }

    @Test
    void aHeldReferenceServesLaterRequestsFromTheHold() throws Exception {
        Map<List<Object>, List<Map<String, Object>>> held = new HashMap<>();
        List<Long> storedAt = new ArrayList<>();
        KeyedReference.Hold hold = new KeyedReference.Hold() {
            @Override
            public long version() {
                return 7L;
            }

            @Override
            public List<Map<String, Object>> peek(List<Object> key) {
                return held.get(key);
            }

            @Override
            public void store(List<Object> key, List<Map<String, Object>> rows, long version) {
                held.put(key, rows);
                storedAt.add(version);
            }
        };
        EnrichSpec spec = perRow(URL, null, new ResultCacheSpec("30s", List.of()));

        // Request one: everything is fetched and stored with the version read before the fetch.
        reference("a", spec).enrich(environment(new ReferenceMemo(), hold, gateway), Map.of(),
                rows("P1", "P9"));
        assertThat(calls).hasSize(2);
        assertThat(held).containsOnlyKeys(List.of("P1"), List.of("P9"));
        assertThat(storedAt).containsExactly(7L, 7L);

        // Request two, a fresh memo: the hold answers P1 and the absent P9; only P2 is fetched.
        List<Map<String, Object>> enriched = reference("a", spec)
                .enrich(environment(new ReferenceMemo(), hold, gateway), Map.of(),
                        rows("P1", "P2", "P9"));
        assertThat(names(enriched)).containsExactly("http-P1", "http-P2", null);
        assertThat(calls).hasSize(3);
        assertThat(calls.get(2)).endsWith("/partners/P2");
    }

    @Test
    void aDegradedFetchRemembersNothing() throws Exception {
        ReferenceMemo memo = new ReferenceMemo();
        List<Object> stored = new ArrayList<>();
        KeyedReference.Hold hold = new KeyedReference.Hold() {
            @Override
            public long version() {
                return 0L;
            }

            @Override
            public List<Map<String, Object>> peek(List<Object> key) {
                return null;
            }

            @Override
            public void store(List<Object> key, List<Map<String, Object>> rows, long version) {
                stored.add(key);
            }
        };
        OutboundGateway dead = new OutboundGateway() {
            @Override
            public Map<String, Object> call(HttpCallSpec spec, Map<String, Object> context) {
                throw new IllegalStateException("partner system down");
            }

            @Override
            public Map<String, Object> call(HttpCallSpec spec, byte[] body,
                    Map<String, String> headers) {
                throw new IllegalStateException("partner system down");
            }

            @Override
            public OutboundGateway.RawResponse exchange(HttpCallSpec spec, byte[] body,
                    Map<String, String> headers) {
                throw new IllegalStateException("partner system down");
            }
        };
        KeyedReference reference = reference("a",
                perRow(URL, "empty", new ResultCacheSpec("30s", List.of())));
        List<Map<String, Object>> enriched = reference.enrich(environment(memo, hold, dead),
                Map.of(), rows("P1"));
        assertThat(names(enriched)).containsExactly((Object) null);
        assertThat(degraded).containsExactly("a");
        assertThat(memo.size(reference.identity())).isZero();
        assertThat(stored).isEmpty();
    }

    @Test
    void theIdentityIsTheStatementOrTheCall() {
        assertThat(reference("a", perRow(URL, null, null)).identity())
                .isEqualTo("http:GET " + URL);
        EnrichSpec sibling = new EnrichSpec(ON, null, null, "partners", null, "lines", null,
                null, null);
        assertThat(reference("b", sibling).identity()).isEqualTo("source:partners");
    }
}
