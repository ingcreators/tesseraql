package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A {@code kind: prompt} document under {@code mcp/} — a parameterized, reusable message
 * template the connecting agent surfaces to its model (an IDE slash command, say).
 *
 * <p>A prompt is pure text: it compiles to no route, runs no SQL, and carries no recipe, which
 * is why it was read straight out of the parsed tree instead of through the route model. The
 * cost of having no record was that nothing described the document's shape, so a typo'd
 * {@code templat:} left a prompt that rendered nothing while every other mcp document family
 * had its keys checked (docs/lint-restructure.md decision 3). This record is that description,
 * and the loader reads it — a shape nothing loads is a shape that drifts.
 *
 * @param version  the DSL version, {@code tesseraql/v1}
 * @param id       the prompt name (the {@code prompts/list} / {@code prompts/get} identifier)
 * @param kind     the document discriminator, {@code prompt}
 * @param description the hint the client shows beside the prompt
 * @param input    the declared arguments, keyed by name and surfaced in authored order
 * @param template the app-relative path (colocated by convention) of the message template
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PromptDefinition(String version, String id, String kind, String description,
        Map<String, Argument> input, String template) {

    public PromptDefinition {
        // Insertion-ordered: the arguments are surfaced to the client in the order they are
        // written, and Map.copyOf randomizes.
        input = input == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(input));
    }

    /**
     * One declared prompt argument. MCP prompt arguments travel as strings, so {@code type:}
     * documents the value the template expects rather than constraining it.
     *
     * @param type        the value the template expects
     * @param required    whether the client must supply it
     * @param description the hint the client shows beside the argument
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Argument(String type, boolean required, String description) {
    }
}
