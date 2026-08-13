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
 * <p>{@link #DISPLAY_ONLY} is empty and {@link #UNWIRED} holds one entry — the 2026-07-25
 * audit retired or wired every dead field, and the one that arrived since came in red,
 * which is the point.
 */
public final class YamlSurfaceConsumers {

    /** {@code "Record#component" -> why display-only is the intended contract}. */
    public static final Map<String, String> DISPLAY_ONLY = Map.of();

    /** {@code "Record#component" -> why it is declared but consumed nowhere (+ issue)}. */
    public static final Map<String, String> UNWIRED = Map.of(
            // Pre-existing, and surfaced the day a prompt got a model: docs/app-mcp.md teaches
            // `type: string` on a prompt argument, and the raw-tree loader it replaced read only
            // description and required. Nothing can consume it as written — an MCP prompt
            // argument travels as name/description/required, so a type has nowhere to go on the
            // wire and nothing to constrain. Registered rather than deleted because deleting it
            // would refuse a key the published guide asks authors to write; whether it becomes
            // part of the argument's description or leaves the surface is a product decision.
            "PromptDefinition.Argument#type",
            "documented in docs/app-mcp.md but consumed by nothing - MCP prompt arguments"
                    + " carry name/description/required only");

    private YamlSurfaceConsumers() {
    }
}
