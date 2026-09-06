package io.tesseraql.core.util;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Insertion-ordered immutable map copies (docs/deterministic-output.md).
 *
 * <p>{@link Map#of} and {@link Map#copyOf} iterate in an order derived from a per-JVM salt —
 * stable within one process, different across processes. A map built with either and later
 * iterated therefore makes whatever it reaches differ between runs of identical code over
 * identical input: a rendered form, a serialized payload, a rewritten configuration file, a signed
 * document. These copies iterate in the order the entries were declared, on every boot.
 *
 * <p>There are two methods because rejecting a null value is behaviour rather than a detail.
 * {@code Map.copyOf} throwing on one is what turns a value-less key in a loaded model map into a
 * coded schema error, so {@link #map(Map)} keeps that contract and only adds the offending key to
 * the message. A site that carries null values deliberately — an absent principal claim, an unset
 * decision output — says so by naming {@link #mapAllowingNulls(Map)} instead, which is why the
 * null-permitting variant is spelled out rather than being the default.
 */
public final class OrderedCopies {

    private OrderedCopies() {
    }

    /**
     * An insertion-ordered unmodifiable copy that rejects null keys and null values, as
     * {@link Map#copyOf} does — naming the offending key, which {@code Map.copyOf} cannot.
     *
     * @throws NullPointerException if the map, any key, or any value is null
     */
    public static <K, V> Map<K, V> map(Map<K, V> source) {
        Objects.requireNonNull(source, "source");
        Map<K, V> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Objects.requireNonNull(key, "A null key is not a valid entry");
            Objects.requireNonNull(value, () -> "Key '" + key + "' has a null value");
            copy.put(key, value);
        });
        return Collections.unmodifiableMap(copy);
    }

    /**
     * An insertion-ordered unmodifiable copy that permits null values, for the sites that carry
     * them deliberately. Prefer {@link #map(Map)}: a null that arrives by accident is worth
     * failing on at the point it arrives.
     *
     * @throws NullPointerException if the map itself is null
     */
    public static <K, V> Map<K, V> mapAllowingNulls(Map<K, V> source) {
        Objects.requireNonNull(source, "source");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
