package io.tesseraql.yaml;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Builds the YAML {@link ObjectMapper} every parse path shares, with explicit
 * {@link StreamReadConstraints} rather than whatever the resolved Jackson/SnakeYAML defaults
 * happen to be (docs/security-hardening.md). The runtime editor endpoints feed request bodies
 * into these mappers, so a bounded nesting depth is a hard requirement, not a nicety — a
 * dependency bump must not be able to widen it.
 */
public final class YamlMappers {

    private YamlMappers() {
    }

    /** A YAML mapper with explicit read constraints, for every parse path. */
    public static ObjectMapper constrained() {
        YAMLFactory factory = new YAMLFactory();
        // The bounds come from JsonLimits, one source with the JSON factory and the local
        // factories in the modules below yaml, so they cannot drift apart.
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(io.tesseraql.core.json.JsonLimits.MAX_NESTING_DEPTH)
                .maxStringLength(io.tesseraql.core.json.JsonLimits.MAX_STRING_LENGTH)
                .maxNameLength(io.tesseraql.core.json.JsonLimits.MAX_NAME_LENGTH)
                .build());
        // A repeated key is an error, not a last-one-wins merge. Every authored map is a
        // namespace an author names things in — sources, steps, inputs, validation rules — and
        // silently keeping the second `main:` is the shape of bug this codebase keeps finding:
        // the document says one thing and the runtime holds another. It matters more now that
        // reads share one `sources:` map (docs/unified-sources.md): the collision a lint used
        // to catch across two maps is a duplicate key inside one.
        factory.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        return new ObjectMapper(factory);
    }
}
