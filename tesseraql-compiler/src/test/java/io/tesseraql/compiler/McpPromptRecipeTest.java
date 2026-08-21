package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The {@code prompt-text} recipe (docs/prompt-as-recipe.md): a prompt compiles to the
 * {@code mcp.prompt.<id>} pipeline through the same head and the same binders every other read
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
                .hasMessageContaining("TQL-ROUTE-3116")
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
                .hasMessageContaining("TQL-ROUTE-3117")
                .hasMessageContaining("response.text");
    }

    /** Compiles the fixture app and maps each route id to its processors' simple class names. */
    private static Map<String, List<String>> compile(Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("prompt-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
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
