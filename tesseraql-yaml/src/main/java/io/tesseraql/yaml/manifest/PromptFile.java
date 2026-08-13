package io.tesseraql.yaml.manifest;

import java.nio.file.Path;
import java.util.List;

/**
 * An application-declared MCP prompt, discovered under {@code mcp/} as a {@code kind: prompt}
 * document (Studio backlog G follow-on; the dev-tool {@code studio_copilot} prompt was the first
 * consumer of the prompts primitive). A prompt is a parameterized, reusable message template the
 * connecting agent surfaces to its model (an IDE slash command, say): the runtime renders the
 * colocated {@code template} (Thymeleaf TEXT mode) against the supplied argument values and returns
 * it as a {@code prompts/get} user message.
 *
 * <p>A prompt that declares a {@code recipe:} is a route like its three siblings
 * (docs/prompt-as-recipe.md): it carries {@code definition}, compiles to
 * {@code direct:mcp.prompt.<id>}, and renders its message from the route's {@code response.text:}.
 * A prompt that declares none is the older, pure-text form — no route, no SQL, no per-prompt
 * security beyond the MCP endpoint's own — and carries {@code template} instead. Both forms exist
 * for slice 1 only; slice 2 moves every prompt to the route parser and deletes {@code template}
 * along with {@code PromptDefinition}.
 *
 * @param source      the source file path within the app home
 * @param id          the prompt name (the {@code prompts/list} / {@code prompts/get} identifier)
 * @param description the prompt description for the MCP client, or null
 * @param arguments   the declared arguments, in document order
 * @param template    the app-relative path (colocated by convention) of the message template, or
 *                    null when the prompt is a route
 * @param definition  the compiled route definition, or null when the prompt is pure text
 */
public record PromptFile(Path source, String id, String description, List<Argument> arguments,
        String template, io.tesseraql.yaml.model.RouteDefinition definition) {

    public PromptFile {
        arguments = arguments == null ? List.of() : List.copyOf(arguments);
    }

    /** One declared prompt argument: its {@code name}, an optional {@code description}, and whether required. */
    public record Argument(String name, String description, boolean required) {
    }
}
