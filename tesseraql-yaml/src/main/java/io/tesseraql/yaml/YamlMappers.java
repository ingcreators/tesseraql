package io.tesseraql.yaml;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;
import tools.jackson.dataformat.yaml.YAMLReadFeature;

/**
 * Builds the YAML {@link ObjectMapper} every parse path shares, with explicit
 * {@link StreamReadConstraints} rather than whatever the resolved Jackson/snakeyaml-engine
 * defaults happen to be (docs/security-hardening.md). The runtime editor endpoints feed request
 * bodies into these mappers, so a bounded nesting depth is a hard requirement, not a nicety — a
 * dependency bump must not be able to widen it.
 *
 * <p>Plain scalars are read with YAML 1.2's core schema, the editor's reading
 * ({@link CoreSchemaYamlFactory}, docs/jackson-3.md decision 6), and the mapper carries
 * Jackson 2's observable defaults ({@link JacksonDefaults}).
 */
public final class YamlMappers {

    private YamlMappers() {
    }

    /** A YAML mapper with explicit read constraints, for every parse path. */
    public static ObjectMapper constrained() {
        // The bounds come from JsonLimits, one source with the JSON factory and the local
        // factories in the modules below yaml, so they cannot drift apart.
        YAMLFactory factory = CoreSchemaYamlFactory.coreSchemaBuilder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(io.tesseraql.core.json.JsonLimits.MAX_NESTING_DEPTH)
                        .maxStringLength(io.tesseraql.core.json.JsonLimits.MAX_STRING_LENGTH)
                        .maxNameLength(io.tesseraql.core.json.JsonLimits.MAX_NAME_LENGTH)
                        .build())
                // A repeated key is an error, not a last-one-wins merge. Every authored map is a
                // namespace an author names things in — sources, steps, inputs, validation rules —
                // and silently keeping the second `main:` is the shape of bug this codebase keeps
                // finding: the document says one thing and the runtime holds another. It matters
                // more now that reads share one `sources:` map (docs/unified-sources.md): the
                // collision a lint used to catch across two maps is a duplicate key inside one.
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                // An empty value is null, as in Jackson 2 and the core schema. It is the feature's
                // documented default, but a factory from YAMLFactory.builder() starts its YAML read
                // features at zero in 3.1 (new YAMLFactory() does not), so it is stated here.
                .enable(YAMLReadFeature.EMPTY_STRING_AS_NULL)
                .build();
        return JacksonDefaults.pin(YAMLMapper.builder(factory)).build();
    }
}
