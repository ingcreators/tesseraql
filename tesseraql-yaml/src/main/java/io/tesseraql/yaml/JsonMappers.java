package io.tesseraql.yaml;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.json.JsonLimits;

/**
 * Builds the JSON {@link ObjectMapper} every parse path shares, with explicit
 * {@link StreamReadConstraints} rather than whatever the resolved Jackson defaults happen to be
 * — the sibling of {@link YamlMappers}, which docs/security-hardening.md hardened while the
 * JSON side was never swept (docs/duplication-consolidation.md, campaign 4): seventy-seven bare
 * {@code new ObjectMapper()} constructions, several of them parsing untrusted request bodies,
 * carried no declared bound at all.
 *
 * <p>Each call returns a fresh mapper, so a caller that configures its instance (lenient
 * unknowns, indented output) affects nobody else. The bounds come from
 * {@link JsonLimits}, shared with the YAML factory and with the local factories in the modules
 * below this one.
 */
public final class JsonMappers {

    private JsonMappers() {
    }

    /** A JSON mapper with explicit read constraints, for every parse path. */
    public static ObjectMapper constrained() {
        return new ObjectMapper(constrainedFactory().build());
    }

    /**
     * The same mapper writing ASCII only: every character above U+007F as a JSON
     * {@code \\uXXXX} escape. For JSON that travels in an HTTP header — htmx's
     * {@code HX-Trigger} — where the transport carries one byte per character and the edge
     * folds anything above U+00FF to {@code ?} (docs/edge-hygiene.md E3).
     */
    public static ObjectMapper constrainedAscii() {
        return new ObjectMapper(constrainedFactory()
                .enable(com.fasterxml.jackson.core.json.JsonWriteFeature.ESCAPE_NON_ASCII)
                .build());
    }

    private static com.fasterxml.jackson.core.JsonFactoryBuilder constrainedFactory() {
        return new com.fasterxml.jackson.core.JsonFactoryBuilder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(JsonLimits.MAX_NESTING_DEPTH)
                        .maxStringLength(JsonLimits.MAX_STRING_LENGTH)
                        .maxNameLength(JsonLimits.MAX_NAME_LENGTH)
                        .build());
    }
}
