package io.tesseraql.security;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.json.JsonLimits;

/**
 * The constrained JSON mapper for this module's parse paths — JWT payloads and JWKS documents,
 * both attacker-influenceable. This module sits below {@code tesseraql-yaml}, so it cannot use
 * {@code JsonMappers}; the construction is local and the bounds come from
 * {@link JsonLimits}, one source with every other factory
 * (docs/duplication-consolidation.md, campaign 4).
 */
public final class SecurityJson {

    private SecurityJson() {
    }

    /** A JSON mapper with explicit read constraints. */
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
