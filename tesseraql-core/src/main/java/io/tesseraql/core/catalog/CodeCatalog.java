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

    /**
     * One row of the catalog, in the order a form should offer it. {@code language} is the
     * BCP-47 tag the label is written in, or {@code null} for a catalog with no language column
     * — the row then answers in every language, which is what a single-language app has.
     */
    public record Entry(Object key, String label, boolean active, String language) {

        /** A row with no language dimension. */
        public Entry(Object key, String label, boolean active) {
            this(key, label, active, null);
        }
    }

    private final String name;
    private final Map<Object, String> labels;
    private final List<Entry> entries;
    private final String language;

    private CodeCatalog(String name, Map<Object, String> labels, List<Entry> entries,
            String language) {
        this.name = name;
        this.labels = Collections.unmodifiableMap(labels);
        this.entries = List.copyOf(entries);
        this.language = language;
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
        return new CodeCatalog(name, labels, rows, null);
    }

    /**
     * The view of this catalog written in one language (docs/lookups.md, decision 12).
     *
     * <p>Language is a dimension of the catalog, not part of the key, so this returns the same
     * type the call site already holds: {@code codes.取引区分.of(row.取引区分)} is the call in
     * every language. What changes is which rows carry the labels.
     *
     * <p>Three properties the narrowing has to keep:
     *
     * <ul>
     * <li><b>The key set does not narrow.</b> A code exists whether or not it is translated, so
     * {@link #has} still answers over every language — validation must never reject a code for
     * a missing translation (decision 11).</li>
     * <li><b>A missing label falls back to the default language</b>, not to the raw code. A
     * half-translated master reads as the untranslated name, which a person can act on.</li>
     * <li><b>Rows with no language at all belong to every language.</b> A catalog that has not
     * grown a language column keeps working unchanged.</li>
     * </ul>
     */
    public CodeCatalog inLanguage(String tag, String defaultTag) {
        if (entries.stream().allMatch(entry -> entry.language() == null)) {
            return this;
        }
        // One entry per code, in the source's order, carrying the label of the language that
        // wins: the requested one, else the default, else a row that declares no language.
        Map<Object, Entry> chosen = new LinkedHashMap<>();
        Map<Object, Integer> rank = new LinkedHashMap<>();
        for (Entry row : entries) {
            Object key = canonical(row.key());
            int candidate = rankOf(row.language(), tag, defaultTag);
            Integer held = rank.get(key);
            if (held == null) {
                // The first row for a code fixes its identity and its active flag, whatever
                // language it happens to be written in.
                chosen.put(key, new Entry(row.key(), candidate < 3 ? row.label() : null,
                        row.active(), tag));
                rank.put(key, candidate);
            } else if (candidate < held) {
                chosen.put(key, new Entry(chosen.get(key).key(), row.label(),
                        chosen.get(key).active(), tag));
                rank.put(key, candidate);
            }
        }
        Map<Object, String> narrowed = new LinkedHashMap<>();
        List<Entry> offered = new ArrayList<>();
        chosen.forEach((key, entry) -> {
            // A code with no label in any resolvable language keeps its place in the key set —
            // it exists, so has() says so and of() renders the code itself.
            narrowed.put(key, entry.label());
            offered.add(entry);
        });
        return new CodeCatalog(name, narrowed, offered, tag);
    }

    /** The language this view is written in, or {@code null} when the catalog has no language. */
    public String language() {
        return language;
    }

    /** How well a row's language answers the request: 0 requested, 1 default, 2 unlabelled. */
    private static int rankOf(String rowLanguage, String tag, String defaultTag) {
        if (rowLanguage == null) {
            return 2;
        }
        if (tag != null && matches(rowLanguage, tag)) {
            return 0;
        }
        if (defaultTag != null && matches(rowLanguage, defaultTag)) {
            return 1;
        }
        return 3;
    }

    /** RFC 4647-style: {@code ja} matches a requested {@code ja-JP} and the other way round. */
    private static boolean matches(String rowLanguage, String requested) {
        if (rowLanguage.equalsIgnoreCase(requested)) {
            return true;
        }
        String row = rowLanguage.toLowerCase(java.util.Locale.ROOT);
        String want = requested.toLowerCase(java.util.Locale.ROOT);
        return want.startsWith(row + "-") || row.startsWith(want + "-");
    }

    /** The catalog's name, for a message that has to say which one. */
    public String name() {
        return name;
    }

    /**
     * The name behind a code — and, when the catalog does not carry that code, the code itself.
     * Varargs so a composite catalog reads the same as a single-keyed one at the call site.
     *
     * <p>The fallback is the point. A code with no name is a gap in the master data, not a
     * reason to blank a cell: an order that carries a code retired before the labels were
     * imported still has to say <em>something</em>, and the code is the truest thing available.
     * Returning {@code null} here would put that decision in every template, and a template
     * that forgets it renders an empty column. Existence is {@link #has} — the question
     * validation asks, and the only one whose answer depends on the key set alone.
     */
    public String of(Object... key) {
        Object canonical = canonical(key.length == 1 ? key[0] : List.of(key));
        String label = labels.get(canonical);
        if (label != null) {
            return label;
        }
        // The code as it reads: one part as itself, a composite as its parts over a slash,
        // which is at least a code an operator can search the master for.
        if (canonical instanceof List<?> parts) {
            return parts.stream().map(part -> part == null ? "" : part.toString())
                    .reduce((a, b) -> a + "/" + b).orElse("");
        }
        return canonical == null ? null : canonical.toString();
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
