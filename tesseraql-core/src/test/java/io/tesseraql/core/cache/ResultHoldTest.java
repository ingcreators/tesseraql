package io.tesseraql.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.telemetry.Meter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The one hold and its key (docs/caching.md decisions 3 and 4): what the key carries, what
 * the hold never does — serve another tenant's rows, serve past its age or behind its stamp,
 * hold an error, hand out its own list, load twice for one key — and what it counts.
 */
class ResultHoldTest {

    private static final HoldSpec ORDERS = new HoldSpec("orders.list", "main", "main", 30_000L,
            List.of("orders"));

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final Map<String, Long> stampRows = new HashMap<>();
    private final TableStamps stamps = new TableStamps() {
        @Override
        public long versionOf(Collection<String> tables) {
            return tables.stream().mapToLong(t -> stampRows.getOrDefault(t, 0L)).max()
                    .orElse(0L);
        }

        @Override
        public void bump(Collection<String> tables) {
            tables.forEach(t -> stampRows.merge(t, 1L, Long::sum));
        }
    };
    private final RecordingMeter meter = new RecordingMeter();

    private ResultHold hold() {
        return new ResultHold(3, 5, true, stamps, meter, clock::get);
    }

    private static BoundSql bound(String sql, Map<String, Object> params) {
        return SqlRenderer.render(sql, params);
    }

    private static String key(String datasource, String tenant, BoundSql bound) {
        return ResultKey.of(datasource, tenant, "web/orders/list.sql", 100, "fail", bound)
                .orElseThrow();
    }

    private static ResultHold.Rows rows(String... notes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String note : notes) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("note", note);
            out.add(row);
        }
        return new ResultHold.Rows(out, false);
    }

    // --- the key ---------------------------------------------------------------------------

    @Test
    void theKeyCarriesThePoolTheTenantTheStatementTheBoundTheTextAndEveryBind() {
        BoundSql a = bound("select * from t where id = /* id */1", Map.of("id", 1));
        String base = key("main", "alpha", a);
        assertThat(key("main", "alpha", a)).isEqualTo(base);
        assertThat(key("crm", "alpha", a)).as("another connector").isNotEqualTo(base);
        assertThat(key("main", "beta", a)).as("another tenant").isNotEqualTo(base);
        assertThat(key("main", null, a)).as("no tenant").isNotEqualTo(base);
        assertThat(ResultKey.of("main", "alpha", "web/orders/other.sql", 100, "fail", a)
                .orElseThrow()).as("another statement").isNotEqualTo(base);
        assertThat(ResultKey.of("main", "alpha", "web/orders/list.sql", 50, "fail", a)
                .orElseThrow()).as("another row bound").isNotEqualTo(base);
        assertThat(ResultKey.of("main", "alpha", "web/orders/list.sql", 100, "warn", a)
                .orElseThrow()).as("another overflow rule").isNotEqualTo(base);
        assertThat(key("main", "alpha",
                bound("select * from t where id = /* id */1 and 1 = 1", Map.of("id", 1))))
                .as("other text").isNotEqualTo(base);
        assertThat(key("main", "alpha",
                bound("select * from t where id = /* id */1", Map.of("id", 2))))
                .as("another bind value").isNotEqualTo(base);
        assertThat(key("main", "alpha",
                bound("select * from t where id = /* id */1", Map.of("id", "1"))))
                .as("the same text as another type").isNotEqualTo(base);
        assertThat(key("main", "alpha",
                bound("select * from t where id = /* id */1", Map.of("id", 1L))))
                .as("Integer versus Long").isNotEqualTo(base);
    }

    @Test
    void aBindWithNoCanonicalTextMakesTheStatementUnkeyable() {
        BoundSql bytes = bound("select * from t where blob = /* b */'x'",
                Map.of("b", new byte[]{1, 2}));
        assertThat(ResultKey.of("main", null, "s", 100, "fail", bytes)).isEmpty();
        Map<String, Object> nullBind = new HashMap<>();
        nullBind.put("id", null);
        assertThat(ResultKey.of("main", null, "s", 100, "fail",
                bound("select * from t where id = /* id */1", nullBind))).isPresent();
        assertThat(ResultKey.render(null)).isEqualTo("null");
        assertThat(ResultKey.render(new BigDecimal("1.50"))).isEqualTo("BigDecimal:1.50");
        assertThat(ResultKey.render(LocalDate.of(2026, 9, 20)))
                .isEqualTo("LocalDate:2026-09-20");
        assertThat(ResultKey.render("a;b")).isEqualTo("String:a;b");
        assertThat(ResultKey.render(List.of(1))).isNull();
    }

    @Test
    void aValueContainingTheSeparatorCannotCollideWithAnotherKey() {
        BoundSql one = bound("select /* a */'x' as a, /* b */'y' as b",
                Map.of("a", "1;2", "b", "3"));
        BoundSql two = bound("select /* a */'x' as a, /* b */'y' as b",
                Map.of("a", "1", "b", "2;3"));
        assertThat(key("main", null, one)).isNotEqualTo(key("main", null, two));
    }

    // --- the hold --------------------------------------------------------------------------

    @Test
    void aFreshEntryServesACopyAndTheLoaderRunsOncePerKey() {
        ResultHold hold = hold();
        AtomicInteger loads = new AtomicInteger();
        ResultHold.Rows first = hold.read("k1", ORDERS, () -> {
            loads.incrementAndGet();
            return rows("a", "b");
        });
        ResultHold.Rows second = hold.read("k1", ORDERS, () -> {
            loads.incrementAndGet();
            return rows("never");
        });
        assertThat(loads).hasValue(1);
        assertThat(second.rows()).extracting(row -> row.get("note")).containsExactly("a", "b");
        // Copy-on-read: mutating what a caller got reaches no later reader.
        second.rows().get(0).put("note", "poisoned");
        second.rows().clear();
        assertThat(first.rows()).hasSize(2);
        assertThat(hold.read("k1", ORDERS, () -> rows("never")).rows())
                .extracting(row -> row.get("note")).containsExactly("a", "b");
        assertThat(meter.count("tesseraql.cache.hits")).isEqualTo(2);
        assertThat(meter.count("tesseraql.cache.misses")).isEqualTo(1);
    }

    @Test
    void anEntryExpiresOnItsOwnAge() {
        ResultHold hold = hold();
        hold.read("k1", ORDERS, () -> rows("old"));
        clock.addAndGet(29_999L);
        assertThat(hold.read("k1", ORDERS, () -> rows("new")).rows().get(0).get("note"))
                .isEqualTo("old");
        clock.addAndGet(1L);
        assertThat(hold.read("k1", ORDERS, () -> rows("new")).rows().get(0).get("note"))
                .isEqualTo("new");
    }

    @Test
    void aStampThatMovedSinceTheLoadExpiresTheEntry() {
        ResultHold hold = hold();
        hold.read("k1", ORDERS, () -> rows("old"));
        // Another node's write: the row moved, this node's entry is behind it.
        stamps.bump(List.of("orders"));
        assertThat(hold.read("k1", ORDERS, () -> rows("new")).rows().get(0).get("note"))
                .isEqualTo("new");
        // A table the entry does not read moving changes nothing.
        stamps.bump(List.of("customers"));
        assertThat(hold.read("k1", ORDERS, () -> rows("newer")).rows().get(0).get("note"))
                .isEqualTo("new");
    }

    @Test
    void thisNodesInvalidationDropsTheEntriesOverTheTableAtOnce() {
        ResultHold hold = hold();
        HoldSpec customers = new HoldSpec("customers.list", "main", "main", 30_000L,
                List.of("customers"));
        hold.read("k1", ORDERS, () -> rows("old"));
        hold.read("k2", customers, () -> rows("c"));
        hold.invalidate(List.of("orders"));
        assertThat(hold.size()).isEqualTo(1);
        assertThat(hold.read("k1", ORDERS, () -> rows("new")).rows().get(0).get("note"))
                .isEqualTo("new");
        assertThat(hold.read("k2", customers, () -> rows("never")).rows().get(0).get("note"))
                .isEqualTo("c");
        assertThat(meter.count("tesseraql.cache.invalidations")).isEqualTo(1);
        hold.clear();
        assertThat(hold.size()).isZero();
    }

    @Test
    void theEntryBoundEvictsTheLeastRecentlyUsed() {
        ResultHold hold = hold();
        hold.read("k1", ORDERS, () -> rows("1"));
        hold.read("k2", ORDERS, () -> rows("2"));
        hold.read("k3", ORDERS, () -> rows("3"));
        hold.read("k1", ORDERS, () -> rows("never")); // k1 is recent again
        hold.read("k4", ORDERS, () -> rows("4")); // evicts k2
        assertThat(hold.size()).isEqualTo(3);
        assertThat(hold.evictions()).isEqualTo(1);
        AtomicInteger loads = new AtomicInteger();
        hold.read("k2", ORDERS, () -> {
            loads.incrementAndGet();
            return rows("2 again");
        });
        assertThat(loads).hasValue(1);
    }

    @Test
    void aResultLargerThanTheRowBoundIsExecutedNotHeld() {
        ResultHold hold = hold();
        AtomicInteger loads = new AtomicInteger();
        for (int i = 0; i < 2; i++) {
            ResultHold.Rows big = hold.read("k1", ORDERS, () -> {
                loads.incrementAndGet();
                return rows("1", "2", "3", "4", "5", "6");
            });
            assertThat(big.rows()).hasSize(6);
        }
        assertThat(loads).hasValue(2);
        assertThat(hold.size()).isZero();
        assertThat(meter.count("tesseraql.cache.bypasses")).isEqualTo(2);
        assertThat(meter.reasons("tesseraql.cache.bypasses")).containsOnly("oversize");
    }

    @Test
    void anUnkeyableReadAndADisabledHoldBypassCounted() {
        ResultHold hold = hold();
        AtomicInteger loads = new AtomicInteger();
        hold.read(null, ORDERS, () -> {
            loads.incrementAndGet();
            return rows("x");
        });
        hold.read(null, ORDERS, () -> {
            loads.incrementAndGet();
            return rows("x");
        });
        assertThat(loads).hasValue(2);
        assertThat(meter.reasons("tesseraql.cache.bypasses")).containsExactly("binds", "binds");

        ResultHold disabled = new ResultHold(3, 5, false, stamps, meter, clock::get);
        disabled.read("k1", ORDERS, () -> rows("x"));
        disabled.read("k1", ORDERS, () -> rows("x"));
        assertThat(disabled.size()).isZero();
        assertThat(meter.reasons("tesseraql.cache.bypasses"))
                .containsExactly("binds", "binds", "disabled", "disabled");
    }

    @Test
    void aStatementThatThrowsIsNotHeldAndATruncatedResultIsHeldWithItsFlag() {
        ResultHold hold = hold();
        assertThatThrownBy(() -> hold.read("k1", ORDERS, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(hold.size()).isZero();
        hold.read("k1", ORDERS, () -> new ResultHold.Rows(rows("a").rows(), true));
        assertThat(hold.read("k1", ORDERS, () -> rows("never")).truncated()).isTrue();
    }

    @Test
    void concurrentReadersOfOneKeyLoadOnce() throws Exception {
        ResultHold hold = hold();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        int readers = 8;
        List<Thread> threads = new ArrayList<>();
        List<ResultHold.Rows> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < readers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    started.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                results.add(hold.read("k1", ORDERS, () -> {
                    loads.incrementAndGet();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                    return rows("once");
                }));
            });
            threads.add(thread);
            thread.start();
        }
        started.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
        assertThat(loads).as("one statement for eight concurrent callers").hasValue(1);
        assertThat(results).hasSize(readers)
                .allSatisfy(r -> assertThat(r.rows().get(0).get("note")).isEqualTo("once"));
    }

    @Test
    void theStatusReportsEveryDeclaredSourceWithItsCounters() {
        ResultHold hold = hold();
        HoldSpec customers = new HoldSpec("customers.list", "main", "crm", 60_000L,
                List.of("customers", "regions"));
        hold.declare(customers);
        hold.read("k1", ORDERS, () -> rows("a"));
        hold.read("k1", ORDERS, () -> rows("a"));
        List<ResultHold.SourceStatus> status = hold.status();
        assertThat(status).extracting(ResultHold.SourceStatus::owner)
                .containsExactly("customers.list", "orders.list");
        ResultHold.SourceStatus orders = status.get(1);
        assertThat(orders.entries()).isEqualTo(1);
        assertThat(orders.hits()).isEqualTo(1);
        assertThat(orders.misses()).isEqualTo(1);
        assertThat(orders.tables()).containsExactly("orders");
        ResultHold.SourceStatus never = status.get(0);
        assertThat(never.entries()).isZero();
        assertThat(never.misses()).isZero();
        assertThat(never.datasource()).isEqualTo("crm");
        assertThat(never.maxAgeMillis()).isEqualTo(60_000L);
    }

    /** docs/caching.md decision 5: one call drops both holds, then raises the stamp. */
    @Test
    void anInvalidationDropsTheCatalogsAndTheHoldThenRaisesTheStamp() {
        ResultHold hold = hold();
        hold.read("k1", ORDERS, () -> rows("old"));
        List<String> dropped = new ArrayList<>();
        io.tesseraql.core.catalog.CatalogStore catalogs = new io.tesseraql.core.catalog.CatalogStore() {
            @Override
            public Map<String, io.tesseraql.core.catalog.CodeCatalog> catalogs(String tag) {
                return Map.of();
            }

            @Override
            public io.tesseraql.core.catalog.CodeCatalog catalog(String name) {
                return null;
            }

            @Override
            public io.tesseraql.core.catalog.CodeCatalog reload(String name) {
                return null;
            }

            @Override
            public void invalidate(Collection<String> tables) {
                dropped.addAll(tables);
            }

            @Override
            public List<Status> status() {
                return List.of();
            }
        };
        Invalidations.of(catalogs, hold, stamps).invalidate(List.of("orders"));
        assertThat(dropped).containsExactly("orders");
        assertThat(hold.size()).isZero();
        assertThat(stamps.versionOf(List.of("orders"))).isEqualTo(1L);
        // Without a store or a hold the stamp is still raised: a peer may hold what this node
        // never did.
        Invalidations.of(null, null, stamps).invalidate(List.of("orders"));
        assertThat(stamps.versionOf(List.of("orders"))).isEqualTo(2L);
    }

    @Test
    void aSpecRequiresAPositiveAgeAndAtLeastOneTable() {
        assertThatThrownBy(() -> new HoldSpec("r", "s", "main", 0L, List.of("t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HoldSpec("r", "s", "main", 1L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new HoldSpec("r", "s", null, 1L, List.of("t")).datasource()).isEqualTo("main");
    }

    /** A meter that remembers every counter increment with its attributes. */
    private static final class RecordingMeter implements Meter {

        private final List<Map.Entry<String, Map<String, String>>> increments = new ArrayList<>();

        @Override
        public Counter counter(String name) {
            return (delta, attributes) -> increments.add(Map.entry(name, attributes));
        }

        long count(String name) {
            return increments.stream().filter(e -> e.getKey().equals(name)).count();
        }

        List<String> reasons(String name) {
            return increments.stream().filter(e -> e.getKey().equals(name))
                    .map(e -> e.getValue().get("reason")).toList();
        }
    }
}
