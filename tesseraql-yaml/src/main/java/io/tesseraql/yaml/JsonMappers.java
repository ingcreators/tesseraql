package io.tesseraql.yaml;

import io.tesseraql.core.json.JsonLimits;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonFactoryBuilder;
import tools.jackson.core.json.JsonWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the JSON {@link ObjectMapper} every parse path shares, with explicit
 * {@link StreamReadConstraints} rather than whatever the resolved Jackson defaults happen to be
 * — the sibling of {@link YamlMappers}, which docs/security-hardening.md hardened while the
 * JSON side was never swept (docs/duplication-consolidation.md, campaign 4): seventy-seven bare
 * {@code new ObjectMapper()} constructions, several of them parsing untrusted request bodies,
 * carried no declared bound at all.
 *
 * <p>Each call returns a fresh mapper, so a caller that configures its instance (lenient
 * unknowns, indented output) — through {@code rebuild()}, a Jackson 3 mapper being immutable —
 * affects nobody else. The bounds come from {@link JsonLimits}, shared with the YAML factory
 * and with the local factories in the modules below this one; the mapper carries Jackson 2's
 * observable defaults ({@link JacksonDefaults}).
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /** A JSON mapper with explicit read constraints, for every parse path. */
    public static ObjectMapper constrained() {
        return JacksonDefaults.pin(JsonMapper.builder(constrainedFactory().build())).build();
    }

    /**
     * The same mapper writing ASCII only: every character above U+007F as a JSON
     * {@code \\uXXXX} escape. For JSON that travels in an HTTP header — htmx's
     * {@code HX-Trigger} — where the transport carries one byte per character and the edge
     * folds anything above U+00FF to {@code ?} (docs/edge-hygiene.md E3).
     */
    public static ObjectMapper constrainedAscii() {
        return JacksonDefaults.pin(JsonMapper.builder(constrainedFactory()
                .enable(JsonWriteFeature.ESCAPE_NON_ASCII)
                .build())).build();
    }

    private static JsonFactoryBuilder constrainedFactory() {
        return JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(JsonLimits.MAX_NESTING_DEPTH)
                        .maxStringLength(JsonLimits.MAX_STRING_LENGTH)
                        .maxNameLength(JsonLimits.MAX_NAME_LENGTH)
                        .build());
    }
}
