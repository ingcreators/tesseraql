package io.tesseraql.security;

import io.tesseraql.core.json.JsonLimits;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

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
        return JsonMapper.builder(JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(JsonLimits.MAX_NESTING_DEPTH)
                        .maxStringLength(JsonLimits.MAX_STRING_LENGTH)
                        .maxNameLength(JsonLimits.MAX_NAME_LENGTH)
                        .build())
                .build())
                // Jackson 2's observable defaults — the same lines as io.tesseraql.yaml
                // .JacksonDefaults, which this module sits below; JacksonDefaultsLedgerTest
                // holds the two equal (docs/jackson-3.md decision 4).
                .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .disable(EnumFeature.READ_ENUMS_USING_TO_STRING)
                .disable(EnumFeature.WRITE_ENUMS_USING_TO_STRING)
                .build();
    }
}
