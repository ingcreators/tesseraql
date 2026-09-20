package io.tesseraql.core.cache;

import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.NoopMeter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The one hold a runtime keeps over the rows its held sources produced (docs/caching.md
 * decision 4). Bounded twice — by entries and by rows per entry — single-flight per key,
 * copy-on-read, and never holding an error: a statement that throws is not an entry, a
 * {@code truncated} result is one with its flag.
 *
 * <p>Staleness is judged in this order: the entry's own {@code maxAge} elapsed, then the
 * version stamp of any of its tables moved since the load ({@link TableStamps}), then serve.
 * The stamp is what carries another node's {@code invalidates:}; this node's is
 * {@link #invalidate}, immediate.
 *
 * <p>Every outcome is counted through the {@link Meter} — {@code tesseraql.cache.hits},
 * {@code .misses}, {@code .bypasses} (with a {@code reason}), {@code .invalidations},
 * {@code .evictions} — with the owner and the source as attributes, and kept per source for
 * the operations surface, which reports the hold and never takes one.
 */
public final class ResultHold {

    /**
     * What one statement produced: its rows and whether the row bound cut them. The list is
     * held as given and never handed out — every read gets {@link #copy a fresh, mutable copy}
     * — so a caller keeps the list shape it always had.
     */
    public record Rows(List<Map<String, Object>> rows, boolean truncated) {
    }

    /** One held entry. */
    private record Held(HoldSpec spec, Rows rows, long loadedAt, long stampAtLoad) {
    }

    /** A statement's execution, run under the per-key lock on a miss. */
    @FunctionalInterface
    public interface Loader<X extends Exception> {
        Rows load() throws X;
    }

    /** Why a read bypassed the hold. */
    public enum Bypass {
        /** The hold is disabled by configuration ({@code tesseraql.cache.enabled: false}). */
        DISABLED("disabled"),
        /** A bind had no canonical text ({@link ResultKey}). */
        BINDS("binds"),
        /** The result carried more rows than {@code maxEntryRows}: executed, not held. */
        OVERSIZE("oversize");

        private final String reason;

        Bypass(String reason) {
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** One held source as the operations surface sees it. */
    public record SourceStatus(String owner, String source, String datasource, long maxAgeMillis,
            List<String> tables, int entries, long hits, long misses, long bypasses,
            long invalidations) {
    }

    /** Per-source counters, kept for the operations row beside the meter. */
    private static final class Counters {
        private final AtomicLong hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong bypasses = new AtomicLong();
        private final AtomicLong invalidations = new AtomicLong();
    }

    private final int maxEntries;
    private final int maxEntryRows;
    private final boolean enabled;
    private final TableStamps stamps;
    private final Meter meter;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Held> entries;
    private final Map<String, Object> loading = new ConcurrentHashMap<>();
    private final Map<String, HoldSpec> declared = new ConcurrentHashMap<>();
    private final Map<String, Counters> counters = new ConcurrentHashMap<>();
    private final AtomicLong evictions = new AtomicLong();

    /** An enabled hold with the production clock. */
    public ResultHold(int maxEntries, int maxEntryRows, TableStamps stamps, Meter meter) {
        this(maxEntries, maxEntryRows, true, stamps, meter, System::currentTimeMillis);
    }

    /**
     * @param maxEntries   how many entries the hold keeps, evicting least-recently-used
     * @param maxEntryRows the largest result held; a larger one is executed and counted
     * @param enabled      {@code false} makes every read a counted bypass — the operator's one
     *                     key when a hold misbehaves ({@code tesseraql.cache.enabled})
     * @param stamps       the per-table versions another node's write raises
     * @param meter        where the outcomes are counted; {@code null} counts nowhere
     * @param clock        epoch millis; injectable for the tests
     */
    public ResultHold(int maxEntries, int maxEntryRows, boolean enabled, TableStamps stamps,
            Meter meter, LongSupplier clock) {
        if (maxEntries <= 0 || maxEntryRows <= 0) {
            throw new IllegalArgumentException("maxEntries and maxEntryRows must be positive");
        }
        this.maxEntries = maxEntries;
        this.maxEntryRows = maxEntryRows;
        this.enabled = enabled;
        this.stamps = stamps == null ? TableStamps.NONE : stamps;
        this.meter = meter == null ? NoopMeter.INSTANCE : meter;
        this.clock = clock;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Held> eldest) {
                if (size() > ResultHold.this.maxEntries) {
                    evictions.incrementAndGet();
                    return true;
                }
                return false;
            }
        };
    }

    /** Whether reads consult the hold at all. */
    public boolean enabled() {
        return enabled;
    }

    /** Registers a declared source so the operations surface lists it before its first read. */
    public void declare(HoldSpec spec) {
        declared.put(spec.label(), spec);
        counters.computeIfAbsent(spec.label(), ignored -> new Counters());
    }

    /**
     * The rows for {@code key}: a copy of the held entry when it is fresh, else the loader's
     * result — executed once under the key's own lock while other callers of the same key
     * wait, stored unless it is larger than the hold admits, and returned as a copy too, so
     * nothing a caller does to its rows reaches the hold.
     *
     * @param key    the identity {@link ResultKey} rendered, or {@code null} for a bind it
     *               could not render — a counted bypass, never a guess
     * @param spec   the source's declaration
     * @param loader the statement's execution
     */
    public <X extends Exception> Rows read(String key, HoldSpec spec, Loader<X> loader)
            throws X {
        declare(spec);
        if (!enabled) {
            return bypass(spec, Bypass.DISABLED, loader);
        }
        if (key == null) {
            return bypass(spec, Bypass.BINDS, loader);
        }
        Held fresh = fresh(key);
        if (fresh != null) {
            return hit(spec, fresh);
        }
        Object lock = loading.computeIfAbsent(key, ignored -> new Object());
        try {
            synchronized (lock) {
                Held rechecked = fresh(key);
                if (rechecked != null) {
                    return hit(spec, rechecked);
                }
                long stampAtLoad = stamps.versionOf(spec.tables());
                Rows loaded = loader.load();
                count(spec, counted -> counted.misses);
                meter.counter("tesseraql.cache.misses").increment(attributes(spec));
                if (loaded.rows().size() > maxEntryRows) {
                    count(spec, counted -> counted.bypasses);
                    meter.counter("tesseraql.cache.bypasses")
                            .increment(attributes(spec, Bypass.OVERSIZE));
                    return loaded;
                }
                synchronized (entries) {
                    entries.put(key, new Held(spec, loaded, clock.getAsLong(), stampAtLoad));
                }
                return copy(loaded);
            }
        } finally {
            loading.remove(key, lock);
        }
    }

    /** Drops every entry whose declaration reads one of {@code tables}; this node only. */
    public void invalidate(Collection<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        synchronized (entries) {
            entries.values().removeIf(held -> held.spec().tables().stream()
                    .anyMatch(tables::contains));
        }
        declared.values().forEach(spec -> {
            if (spec.tables().stream().anyMatch(tables::contains)) {
                count(spec, counted -> counted.invalidations);
                meter.counter("tesseraql.cache.invalidations").increment(attributes(spec));
            }
        });
    }

    /** Drops everything; a reload's move (docs/caching.md decision 11). */
    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    /** How many entries are held right now. */
    public int size() {
        synchronized (entries) {
            return entries.size();
        }
    }

    /** The entry bound. */
    public int maxEntries() {
        return maxEntries;
    }

    /** The per-entry row bound. */
    public int maxEntryRows() {
        return maxEntryRows;
    }

    /** How many entries the bound pushed out. */
    public long evictions() {
        return evictions.get();
    }

    /** Every declared source with its counters, in declaration order; reports, never loads. */
    public List<SourceStatus> status() {
        Map<String, Integer> held = new LinkedHashMap<>();
        synchronized (entries) {
            entries.values().forEach(entry -> held.merge(entry.spec().label(), 1, Integer::sum));
        }
        List<SourceStatus> out = new ArrayList<>();
        declared.values().stream()
                .sorted(java.util.Comparator.comparing(HoldSpec::label))
                .forEach(spec -> {
                    Counters counted = counters.getOrDefault(spec.label(), new Counters());
                    out.add(new SourceStatus(spec.owner(), spec.source(), spec.datasource(),
                            spec.maxAgeMillis(), spec.tables(),
                            held.getOrDefault(spec.label(), 0), counted.hits.get(),
                            counted.misses.get(), counted.bypasses.get(),
                            counted.invalidations.get()));
                });
        return out;
    }

    /** The entry under {@code id} when it is neither past its age nor behind its tables' stamp. */
    private Held fresh(String id) {
        Held held;
        synchronized (entries) {
            held = entries.get(id);
        }
        if (held == null) {
            return null;
        }
        if (clock.getAsLong() - held.loadedAt() >= held.spec().maxAgeMillis()
                || stamps.versionOf(held.spec().tables()) > held.stampAtLoad()) {
            synchronized (entries) {
                entries.remove(id, held);
            }
            return null;
        }
        return held;
    }

    private Rows hit(HoldSpec spec, Held held) {
        count(spec, counted -> counted.hits);
        meter.counter("tesseraql.cache.hits").increment(attributes(spec));
        return copy(held.rows());
    }

    private <X extends Exception> Rows bypass(HoldSpec spec, Bypass why, Loader<X> loader)
            throws X {
        count(spec, counted -> counted.bypasses);
        meter.counter("tesseraql.cache.bypasses").increment(attributes(spec, why));
        return loader.load();
    }

    /** A fresh list of fresh row maps: a hit hands out rows nothing downstream can poison. */
    private static Rows copy(Rows rows) {
        List<Map<String, Object>> copies = new ArrayList<>(rows.rows().size());
        for (Map<String, Object> row : rows.rows()) {
            copies.add(new LinkedHashMap<>(row));
        }
        return new Rows(copies, rows.truncated());
    }

    private void count(HoldSpec spec, java.util.function.Function<Counters, AtomicLong> which) {
        which.apply(counters.computeIfAbsent(spec.label(), ignored -> new Counters()))
                .incrementAndGet();
    }

    private static Map<String, String> attributes(HoldSpec spec) {
        return Map.of("route", spec.owner(), "source", spec.source());
    }

    private static Map<String, String> attributes(HoldSpec spec, Bypass why) {
        return Map.of("route", spec.owner(), "source", spec.source(), "reason", why.reason());
    }
}
