package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * mcp documents get the same unknown-key linting every other surface has. They reuse the route
 * record (which ignores unknown properties) plus keys the loader reads from the raw tree
 * ({@code description}, {@code uri}, {@code mimeType}, {@code ui}) — and until this lint was
 * wired, a typo'd {@code securty:} on a tool dropped the auth declaration in silence while the
 * same typo on a route was flagged.
 */
class AppLinterMcpUnknownKeysTest {

    @Test
    void flagsATypoOnAToolAndAcceptsTheLoaderKeys(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Path mcp = dir.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("pick.sql"), "select 1 as id\n");
        // A clean tool: description and ui are loader-read keys, not typos.
        Files.writeString(mcp.resolve("pick.yml"), """
                version: tesseraql/v1
                id: items.pick
                kind: tool
                recipe: query-json
                description: Picks an item.
                ui: ui://items/pick
                security:
                  policy: app.read
                sources:
                  main:
                    sql:
                      file: pick.sql
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        // The typo that used to vanish: securty is not a key, and the tool served unauthenticated.
        Files.writeString(mcp.resolve("broken.yml"), """
                version: tesseraql/v1
                id: items.broken
                kind: tool
                recipe: query-json
                description: Broken tool.
                securty:
                  policy: app.read
                sources:
                  main:
                    sql:
                      file: pick.sql
                response:
                  json:
                    body:
                      rows: main.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1043")
                        && f.source().contains("broken.yml"))
                .anyMatch(f -> f.message().contains("securty"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.source().contains("pick.yml"));
    }

    @Test
    void flagsATypoOnAResourceAndAUiResource(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Path mcp = dir.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("list.sql"), "select 1 as id\n");
        Files.writeString(mcp.resolve("resource.yml"), """
                version: tesseraql/v1
                id: items.catalog
                kind: resource
                recipe: query-json
                description: The catalog.
                uri: catalog://items
                mimeType: application/json
                securiy:
                  policy: app.read
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        Files.createDirectories(dir.resolve("mcp/ui"));
        Files.writeString(dir.resolve("mcp/ui/panel.html"), "<p>x</p>\n");
        Files.writeString(mcp.resolve("panel.yml"), """
                version: tesseraql/v1
                id: items.panel
                kind: ui
                recipe: page
                description: The panel.
                uri: ui://items/panel
                respnse:
                  html:
                    template: ui/panel.html
                response:
                  html:
                    template: ui/panel.html
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1043"))
                .anyMatch(f -> f.source().contains("resource.yml")
                        && f.message().contains("securiy"))
                .anyMatch(f -> f.source().contains("panel.yml")
                        && f.message().contains("respnse"));
    }
}
