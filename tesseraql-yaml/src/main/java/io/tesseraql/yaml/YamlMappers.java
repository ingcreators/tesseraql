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
final class YamlMappers {

    /**
     * The most nesting a TesseraQL document may carry. Real app YAML nests only a handful of
     * levels; this is generous headroom and far below the depth at which deserialization
     * recursion threatens the stack.
     */
    private static final int MAX_NESTING_DEPTH = 100;

    private YamlMappers() {
    }

    /** A YAML mapper with explicit read constraints, for every parse path. */
    static ObjectMapper constrained() {
        YAMLFactory factory = new YAMLFactory();
        factory.setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                // Explicit rather than implicit: pin the length bounds to Jackson's documented
                // defaults so a dependency change cannot silently remove them.
                .maxStringLength(20_000_000)
                .maxNameLength(65_536)
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
