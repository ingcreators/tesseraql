package io.tesseraql.yaml;

import com.fasterxml.jackson.core.JsonFactory;
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
        return new ObjectMapper(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(JsonLimits.MAX_NESTING_DEPTH)
                        .maxStringLength(JsonLimits.MAX_STRING_LENGTH)
                        .maxNameLength(JsonLimits.MAX_NAME_LENGTH)
                        .build())
                .build());
    }
}
