package io.tesseraql.yaml.manifest;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;

/**
 * An application-declared MCP prompt, discovered under {@code mcp/} as a {@code kind: prompt}
 * document. A prompt is a parameterized, reusable message the connecting agent surfaces to its
 * model (an IDE slash command, say).
 *
 * <p>A prompt is a route, like its three siblings (docs/prompt-as-recipe.md decision 1): it
 * declares {@code recipe: prompt-text}, carries {@code input:}, {@code security:} and
 * {@code sources:}, compiles to {@code direct:mcp.prompt.<id>}, and renders its message from the
 * route's {@code response.text:}. So a prompt can read data, declare a policy, and carry the
 * telemetry and audit every other route carries — none of which the routeless form could.
 *
 * @param source      the source file path within the app home
 * @param id          the prompt name (the {@code prompts/list} / {@code prompts/get} identifier)
 * @param description the prompt description for the MCP client, or null
 * @param arguments   the declared arguments, in document order
 * @param definition  the compiled route definition
 */
public record PromptFile(Path source, String id, String description, List<Argument> arguments,
        RouteDefinition definition) {

    public PromptFile {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** One declared prompt argument: its {@code name}, an optional {@code description}, and whether required. */
    public record Argument(String name, String description, boolean required) {
    }
}
