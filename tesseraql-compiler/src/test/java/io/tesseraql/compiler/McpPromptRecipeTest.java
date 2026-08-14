package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code prompt-text} recipe (docs/prompt-as-recipe.md): a prompt compiles to
 * {@code direct:mcp.prompt.<id>} through the same head and the same binders every other read
 * recipe runs, and ends in the {@code text:} renderer whose output is the message.
 *
 * <p>It is a read, so the two things a prompt cannot be are refused at compile time rather than
 * accepted and dropped: a command step, and a document with nothing to render.
 */
class McpPromptRecipeTest {

    @Test
    void aPromptCompilesToAReadRouteEndingInTheTextRenderer(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  text:
                    template: brief.txt.tpl
                    model:
                      items: main.rows
                """);

        List<String> steps = compile(dir).get("mcp.prompt.items.brief");

        assertThat(steps).containsSubsequence("RouteTelemetry", "RequestBinder", "CatalogBinder",
                "TextResponseRenderer");
        // The declared source runs before the renderer: a prompt that reads data is the point.
        assertThat(steps).containsSubsequence("CatalogBinder", "NamedQueryBinder",
                "TextResponseRenderer");
    }

    @Test
    void aPromptThatWritesIsRefused(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                response:
                  text:
                    template: brief.txt.tpl
                """);

        assertThatThrownBy(() -> compile(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-CAMEL-3116")
                .hasMessageContaining("prompts/get is a read");
    }

    @Test
    void aPromptWithNothingToRenderIsRefused(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);

        assertThatThrownBy(() -> compile(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-CAMEL-3117")
                .hasMessageContaining("response.text");
    }

    /** Compiles the fixture app and maps each route id to its processors' simple class names. */
    private static Map<String, List<String>> compile(Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        Map<String, List<String>> byRoute = new LinkedHashMap<>();
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new RouteCompiler().appName("prompt-test")
                    .compile(manifest, false, null));
            for (org.apache.camel.model.RouteDefinition route : context.getRouteDefinitions()) {
                byRoute.put(route.getRouteId(), processorNames(route.getOutputs()));
            }
        }
        return byRoute;
    }

    private static List<String> processorNames(List<ProcessorDefinition<?>> outputs) {
        List<String> names = new ArrayList<>();
        for (ProcessorDefinition<?> output : outputs) {
            if (output instanceof ProcessDefinition process && process.getProcessor() != null) {
                names.add(process.getProcessor().getClass().getSimpleName());
            } else {
                names.add(output.getClass().getSimpleName());
            }
            names.addAll(processorNames(output.getOutputs()));
        }
        return names;
    }

    /** One prompt document, with the execution and response block under test appended to it. */
    private static void writeApp(Path dir, String body) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: prompt-test
                """);
        Path mcp = Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(mcp.resolve("brief.yml"), """
                version: tesseraql/v1
                id: items.brief
                kind: prompt
                recipe: prompt-text
                description: brief the model on the items

                input:
                  name:
                    type: string

                security:
                  auth: bearer
                  policy: app.read

                """ + body);
        Files.writeString(mcp.resolve("list.sql"), "select id, name from items\n");
        Files.writeString(mcp.resolve("insert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        Files.writeString(mcp.resolve("brief.txt.tpl"), "[(${items})]\n");
    }
}
