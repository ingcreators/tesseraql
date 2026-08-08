package io.tesseraql.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Phase 23 CLI surface: {@code tesseraql new} and {@code tesseraql scaffold crud} compose
 * into a loadable app, and regeneration honors the edit-detection contract end to end.
 */
class ScaffoldCommandsTest {

    @Test
    void newCreatesASkeletonAndRefusesToOverwriteIt(@TempDir Path dir) {
        int first = execute("new", "demo", "--dir", dir.toString());
        assertThat(first).isZero();
        assertThat(dir.resolve("demo/config/tesseraql.yml")).exists();
        assertThat(dir.resolve("demo/db/migration/V1__create_items.sql")).exists();
        assertThat(dir.resolve("demo/tests/smoke-test.yml")).exists();

        int second = execute("new", "demo", "--dir", dir.toString());
        assertThat(second).isNotZero();
    }

    @Test
    void scaffoldCrudGeneratesIdempotentlyAndDetectsEdits(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        String url = "jdbc:h2:" + dir.resolve("scaffold-db");
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(
                    app.resolve("db/migration/V1__create_items.sql")));
        }

        int first = execute("scaffold", "crud", "--app", app.toString(), "--table", "items",
                "--jdbc-url", url);
        assertThat(first).isZero();
        assertThat(app.resolve("web/items/list.view.yml")).exists();
        assertThat(app.resolve("web/items/{id}/update/update.sql")).exists();
        assertThat(app.resolve("tests/items-crud-test.yml")).exists();

        // Regeneration is idempotent: same schema, same bytes, nothing rewritten.
        byte[] before = Files.readAllBytes(app.resolve("web/items/list.view.yml"));
        assertThat(execute("scaffold", "crud", "--app", app.toString(), "--table", "items",
                "--jdbc-url", url)).isZero();
        assertThat(Files.readAllBytes(app.resolve("web/items/list.view.yml"))).isEqualTo(before);

        // A hand edit blocks regeneration of that file until --force.
        Path edited = app.resolve("web/items/{id}/edit.view.yml");
        Files.writeString(edited, Files.readString(edited) + "<!-- customized -->\n");
        assertThat(execute("scaffold", "crud", "--app", app.toString(), "--table", "items",
                "--jdbc-url", url)).isEqualTo(1);
        assertThat(Files.readString(edited)).contains("customized");

        assertThat(execute("scaffold", "crud", "--app", app.toString(), "--table", "items",
                "--jdbc-url", url, "--force")).isZero();
        assertThat(Files.readString(edited)).doesNotContain("customized");
    }

    @Test
    void ejectViewPinsTheTemplateAndFlipsTheRoute(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        Path board = app.resolve("web/board");
        Files.createDirectories(board);
        Files.writeString(board.resolve("get.yml"), """
                version: tesseraql/v1
                id: board.page
                kind: route
                recipe: query-html
                security:
                  auth: public
                sql:
                  file: board.sql
                response:
                  html:
                    view: board.view.yml
                """);
        Files.writeString(board.resolve("board.sql"), "select name from items\n");
        Files.writeString(board.resolve("board.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Board
                columns:
                  - name: name
                """);

        assertThat(execute("scaffold", "eject-view", "--app", app.toString(),
                "--route", "web/board/get.yml")).isZero();
        Path template = board.resolve("board.html");
        assertThat(template).exists();
        assertThat(Files.readString(template)).contains("tesseraql-scaffold-checksum")
                .contains("th:each=\"row : ${sql.rows}\"");
        assertThat(Files.readString(board.resolve("get.yml")))
                .contains("template: board.html").doesNotContain("view: board.view.yml");

        // Ejected means ejected: a rerun finds no view: left to eject.
        assertThat(execute("scaffold", "eject-view", "--app", app.toString(),
                "--route", "web/board/get.yml")).isEqualTo(1);
    }

    @Test
    void scaffoldCrudPicksUpTheRunningEmbeddedDbMarker(@TempDir Path dir) throws Exception {
        assertThat(execute("new", "demo", "--dir", dir.toString())).isZero();
        Path app = dir.resolve("demo");
        String url = "jdbc:h2:" + dir.resolve("marker-db");
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute(Files.readString(
                    app.resolve("db/migration/V1__create_items.sql")));
        }
        // What a running `serve --embedded-db` leaves behind. The skeleton's configured
        // datasource (localhost:5432) does not answer here, so resolution falls through to
        // the marker — no --jdbc-url needed, the first-login hand-off contract.
        Files.createDirectories(app.resolve("work"));
        Files.writeString(app.resolve("work/embedded-db.jdbc"), url + System.lineSeparator());

        assertThat(execute("scaffold", "crud", "--app", app.toString(), "--table", "items"))
                .isZero();
        assertThat(app.resolve("web/items/list.view.yml")).exists();
        assertThat(app.resolve("tests/items-crud-test.yml")).exists();
    }

    private static int execute(String... args) {
        return TesseraqlCli.commandLine().execute(args);
    }
}
