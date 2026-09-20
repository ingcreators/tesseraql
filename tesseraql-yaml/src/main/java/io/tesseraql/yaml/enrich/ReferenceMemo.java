package io.tesseraql.yaml.enrich;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What one request's enrichments have already fetched (docs/caching.md decision 8,
 * docs/audit-low-leads.md F122): per reference identity, the rows each key answered with —
 * an empty list for a key that answered none, so an absent key is not asked for twice either.
 *
 * <p>Request-scoped by where it lives: the first {@code enrich:} block of a request creates it
 * on the exchange and the last one dies with it. Two blocks over one master — a detail page's
 * {@code main} and its {@code history} — therefore cost one lookup per distinct key, and a
 * block asking a different key set fetches only what the earlier ones did not. The
 * granularity is the key, never the batch statement: a memo of statements would never hit
 * when the second block asked a different set.
 *
 * <p>Not thread-safe by design: an exchange's steps run one after another.
 */
public final class ReferenceMemo {

    private final Map<String, Map<Object, List<Map<String, Object>>>> byReference = new HashMap<>();

    /** The rows fetched for {@code key} under {@code reference} in this request, or {@code null}. */
    public List<Map<String, Object>> get(String reference, Object key) {
        Map<Object, List<Map<String, Object>>> keys = byReference.get(reference);
        return keys == null ? null : keys.get(key);
    }

    /** Records what {@code key} answered with — possibly nothing — for the rest of the request. */
    public void put(String reference, Object key, List<Map<String, Object>> rows) {
        byReference.computeIfAbsent(reference, ignored -> new HashMap<>())
                .put(key, rows == null ? List.of() : rows);
    }

    /** How many keys the memo holds under {@code reference}; the tests' window. */
    public int size(String reference) {
        Map<Object, List<Map<String, Object>>> keys = byReference.get(reference);
        return keys == null ? 0 : keys.size();
    }
}
