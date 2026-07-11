package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Live-view lints (docs/realtime.md): {@code emit:} is a command-json key with slug-shaped
 * topics, {@code refreshOn:} is a list-view key, and a topic no route emits is flagged.
 */
class AppLinterLiveViewTest {

    private static void writeApp(Path dir, String emitLine, String viewKind, String refreshOn)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/approve.sql"),
                "update orders set status = 'APPROVED' where id = /* id */ 1\n");
        Files.writeString(dir.resolve("web/orders/post.yml"), """
                version: tesseraql/v1
                id: orders.approve
                kind: route
                recipe: command-json
                %s
                sql:
                  file: approve.sql
                  mode: update
                response:
                  json:
                    status: 200
                """.formatted(emitLine));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/orders.view.yml"), """
                version: tesseraql/v1
                kind: view
                view: %s
                title: Orders
                %s
                """.formatted(viewKind, refreshOn));
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-html
                sql:
                  file: orders.sql
                response:
                  html:
                    view: orders.view.yml
                """);
    }

    @Test
    void aMatchedEmitAndRefreshOnLintClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "emit: orders.changed", "list", "refreshOn: orders.changed");
        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    @Test
    void refreshOnWithoutAnyEmitterWarns(@TempDir Path dir) throws Exception {
        writeApp(dir, "", "list", "refreshOn: orders.changed");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).noneMatch(LintFinding::isError);
        assertThat(findings).anyMatch(finding -> "TQL-VIEW-3312".equals(finding.code())
                && finding.message().contains("orders.changed"));
    }

    @Test
    void aBadTopicNameIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "emit: Orders Changed!", "list", "refreshOn: orders.changed");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1039".equals(finding.code()));
    }

    @Test
    void refreshOnIsAListViewKey(@TempDir Path dir) throws Exception {
        writeApp(dir, "emit: orders.changed", "detail", "refreshOn: orders.changed");
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-VIEW-3311".equals(finding.code()));
    }

    @Test
    void emitOnAQueryRouteIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "", "list", "");
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                emit: orders.changed
                sql:
                  file: orders.sql
                response:
                  json:
                    status: 200
                    body:
                      rows: sql.rows
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(finding -> finding.isError()
                && "TQL-YAML-1038".equals(finding.code()));
    }
}
