package io.tesseraql.core.catalog;

import io.tesseraql.core.rows.JoinKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One loaded code catalog (docs/lookups.md, decision 8): the codes, the name each stands for,
 * and which of them a form still offers.
 *
 * <p>This is what a template holds. Resolution is a map lookup, so a page showing twenty coded
 * columns costs no queries at all, and the call shape is the same whatever the key's arity —
 * {@code codes.取引区分.of(row.取引区分)} — because two spellings of one idea is worse than
 * either spelling.
 *
 * <p>The key set and the labels are kept apart on purpose (decision 11). A code whose label is
 * missing still exists, so validating against the labels would reject a valid code for a
 * missing translation. Likewise, labels resolve over <em>every</em> row while
 * {@link #options()} offers only the active ones: a retired code must still render on last
 * year's orders and must not be offered on today's form.
 */
public final class CodeCatalog {

    /** One row of the catalog, in the order a form should offer it. */
    public record Entry(Object key, String label, boolean active) {
    }

    private final String name;
    private final Map<Object, String> labels;
    private final List<Entry> entries;

    private CodeCatalog(String name, Map<Object, String> labels, List<Entry> entries) {
        this.name = name;
        this.labels = Collections.unmodifiableMap(labels);
        this.entries = List.copyOf(entries);
    }

    /**
     * A catalog over rows already ordered by the source. Keys are canonicalized the way every
     * other composition in the framework canonicalizes them, so a code stored as a driver's
     * INTEGER resolves against a catalog that read it as a BIGINT.
     */
    public static CodeCatalog of(String name, List<Entry> rows) {
        Map<Object, String> labels = new LinkedHashMap<>();
        for (Entry row : rows) {
            labels.putIfAbsent(canonical(row.key()), row.label());
        }
        return new CodeCatalog(name, labels, rows);
    }

    /** The catalog's name, for a message that has to say which one. */
    public String name() {
        return name;
    }

    /**
     * The name behind a code, or {@code null} when the catalog does not carry it. Varargs so a
     * composite catalog reads the same as a single-keyed one at the call site.
     */
    public String of(Object... key) {
        return labels.get(canonical(key.length == 1 ? key[0] : List.of(key)));
    }

    /** Whether the code exists at all — the check validation makes, ignoring labels. */
    public boolean has(Object... key) {
        return labels.containsKey(canonical(key.length == 1 ? key[0] : List.of(key)));
    }

    /** The codes a form offers: the active ones, in the source's order. */
    public List<Entry> options() {
        List<Entry> offered = new ArrayList<>(entries.size());
        entries.forEach(entry -> {
            if (entry.active()) {
                offered.add(entry);
            }
        });
        return List.copyOf(offered);
    }

    /** Every row, including retired codes, for a surface that renders history. */
    public List<Entry> all() {
        return entries;
    }

    /** How many codes the catalog holds, for the operations surface. */
    public int size() {
        return labels.size();
    }

    @SuppressWarnings("unchecked")
    private static Object canonical(Object key) {
        if (key instanceof List<?> parts) {
            List<Object> canonical = new ArrayList<>(parts.size());
            ((List<Object>) parts).forEach(part -> canonical.add(JoinKeys.value(part)));
            return Collections.unmodifiableList(canonical);
        }
        return JoinKeys.value(key);
    }
}
