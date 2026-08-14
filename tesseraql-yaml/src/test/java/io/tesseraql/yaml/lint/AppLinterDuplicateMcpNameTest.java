package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every MCP primitive lives in one flat namespace, and the {@code mcp/} folders name nothing —
 * so two documents in different folders can claim one id. A duplicate tool id used to reach
 * startup as two compiled routes sharing a route id, and a duplicate prompt id as the prompt
 * registry's own {@code IllegalArgumentException}: both name the id and neither names the file
 * that declared it.
 */
class AppLinterDuplicateMcpNameTest {

    private Path app(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        return dir;
    }

    private void tool(Path dir, String folder, String id) throws Exception {
        Path target = dir.resolve("mcp/" + folder);
        Files.createDirectories(target);
        Files.writeString(target.resolve("list.sql"), "select 1\n");
        Files.writeString(target.resolve("tool.yml"), """
                version: tesseraql/v1
                id: %s
                kind: tool
                recipe: query-json
                description: Lists things.
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      rows: main.rows
                """.formatted(id));
    }

    private void prompt(Path dir, String folder, String id) throws Exception {
        Path target = dir.resolve("mcp/" + folder);
        Files.createDirectories(target);
        Files.writeString(target.resolve("draft.txt.tpl"), "Hello\n");
        Files.writeString(target.resolve("prompt.yml"), """
                version: tesseraql/v1
                id: %s
                kind: prompt
                recipe: prompt-text
                description: Drafts something.
                response:
                  text:
                    template: draft.txt.tpl
                """.formatted(id));
    }

    @Test
    void twoToolsInDifferentFoldersMayNotShareAnId(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        tool(app, "sales/orders", "orders.list");
        tool(app, "ops/orders", "orders.list");

        assertThat(new AppLinter().lint(app)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-MCP-1014");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("orders.list").contains("tool");
        });
    }

    @Test
    void twoPromptsInDifferentFoldersMayNotShareAnId(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        prompt(app, "sales", "draft.welcome");
        prompt(app, "ops", "draft.welcome");

        assertThat(new AppLinter().lint(app)).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-MCP-1014");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("draft.welcome").contains("prompt");
        });
    }

    /** A tool and a prompt are addressed through different registries, so the names may meet. */
    @Test
    void aToolAndAPromptMayShareAName(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        tool(app, "sales", "orders");
        prompt(app, "ops", "orders");

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().equals("TQL-MCP-1014"));
    }

    /** Folders organize; distinct ids in distinct folders are the point of having folders. */
    @Test
    void foldersOrganizeWithoutNaming(@TempDir Path dir) throws Exception {
        Path app = app(dir);
        tool(app, "sales/orders", "orders.list");
        tool(app, "ops/health", "health.check");

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().equals("TQL-MCP-1014"));
    }
}
