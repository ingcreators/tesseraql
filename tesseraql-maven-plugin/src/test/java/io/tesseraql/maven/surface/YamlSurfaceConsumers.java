package io.tesseraql.maven.surface;

import java.util.Map;

/**
 * The human half of the YAML-surface consumer guard (docs/yaml-surface-consumers.md).
 * The scan in {@link ModelFieldConsumerScan} derives every component's consumer set
 * mechanically; this registry records only what a scan cannot decide, and the guard
 * probes every entry so a registration can never be a wish:
 *
 * <ul>
 * <li>{@link #DISPLAY_ONLY} — components <em>meant</em> to be read only by the docs
 * portal or Studio, each with a one-line justification. An entry for a component that
 * has a behavioral consumer, or none at all, fails the guard. This is the entry that
 * would have caught {@code security.provider}: it had consumers, and all of them only
 * printed it.</li>
 * <li>{@link #UNWIRED} — known-dead components, each with the reason it is still
 * declared. An entry for a component that has any consumer fails the guard; a dead
 * component missing from this map fails the build outright: <em>wire it or don't
 * declare it</em>.</li>
 * </ul>
 *
 * <p>Both maps are empty today — the 2026-07-25 audit retired or wired every dead
 * field, and no surviving component is consumed exclusively by display surfaces. That
 * emptiness is the point: the next dead field arrives red.
 */
public final class YamlSurfaceConsumers {

    /** {@code "Record#component" -> why display-only is the intended contract}. */
    public static final Map<String, String> DISPLAY_ONLY = Map.of();

    /** {@code "Record#component" -> why it is declared but consumed nowhere (+ issue)}. */
    public static final Map<String, String> UNWIRED = Map.of();

    private YamlSurfaceConsumers() {
    }
}
