package io.tesseraql.yaml.release;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "What does this deploy change" (roadmap Phase 46): the release diff over two app trees —
 * routes, API contract, the migration list the deploy runs, policy changes, and the
 * table-level schema delta from the introspection sidecars.
 */
class ReleaseDiffTest {

    private Path app(Path dir, boolean candidate) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: diffed
                  security:
                    policies:
                      app.read:
                        anyOf:
                          - role: %s
                      %s
                """.formatted(candidate ? "APP_READ_V2" : "APP_READ",
                candidate ? "app.write:\n        anyOf:\n          - role: APP_WRITE" : ""));

        Path items = dir.resolve("web/api/items");
        Files.createDirectories(items);
        Files.writeString(items.resolve("items.sql"), "select 1 as x\n");
        Files.writeString(items.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: app.read
                sql:
                  file: items.sql
                  mode: query
                response:
                  json:
                    body:
                      data: sql.rows
                      %s
                """.formatted(candidate ? "extra: sql.rowCount" : ""));

        if (!candidate) {
            // A route only the baseline serves -> REMOVED in the diff.
            Path legacy = dir.resolve("web/api/legacy");
            Files.createDirectories(legacy);
            Files.writeString(legacy.resolve("legacy.sql"), "select 1 as x\n");
            Files.writeString(legacy.resolve("get.yml"), """
                    version: tesseraql/v1
                    id: legacy.list
                    kind: route
                    recipe: query-json
                    security:
                      auth: bearer
                    sql:
                      file: legacy.sql
                      mode: query
                    response:
                      json:
                        body:
                          data: sql.rows
                    """);
        } else {
            // A route only the candidate serves -> ADDED, plus a new migration to run.
            Path orders = dir.resolve("web/api/orders");
            Files.createDirectories(orders);
            Files.writeString(orders.resolve("orders.sql"), "select 1 as x\n");
            Files.writeString(orders.resolve("get.yml"), """
                    version: tesseraql/v1
                    id: orders.list
                    kind: route
                    recipe: query-json
                    security:
                      auth: bearer
                    sql:
                      file: orders.sql
                      mode: query
                    response:
                      json:
                        body:
                          data: sql.rows
                    """);
        }

        Files.createDirectories(dir.resolve("db/migration"));
        Files.writeString(dir.resolve("db/migration/V1__base.sql"), "select 1;\n");
        if (candidate) {
            Files.writeString(dir.resolve("db/migration/V2__orders.sql"),
                    "create table orders (id int);\n");
        }

        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/schema.json"), """
                {"schemaVersion":1,"generatedAt":"x","datasources":{"main":{"tables":[
                  {"name":"items","type":"TABLE","schema":null,
                   "columns":[{"name":"id","jdbcType":4,"sqlTypeName":"int4","size":10,
                               "nullable":false,"autoincrement":true,"defaultValue":null}%s],
                   "primaryKey":["id"],"foreignKeys":[],"uniqueIndexes":[]}%s
                ]}}}
                """.formatted(
                candidate
                        ? ",{\"name\":\"note\",\"jdbcType\":12,\"sqlTypeName\":\"varchar\","
                                + "\"size\":40,\"nullable\":true,\"autoincrement\":false,"
                                + "\"defaultValue\":null}"
                        : "",
                candidate
                        ? ",{\"name\":\"orders\",\"type\":\"TABLE\",\"schema\":null,"
                                + "\"columns\":[],\"primaryKey\":[],\"foreignKeys\":[],"
                                + "\"uniqueIndexes\":[]}"
                        : ""));
        return dir;
    }

    @Test
    void diffsRoutesApiMigrationsPoliciesAndSchema(@TempDir Path base, @TempDir Path next)
            throws Exception {
        app(base, false);
        app(next, true);

        ReleaseDiff.Report report = ReleaseDiff.between(base, next);

        assertThat(report.routes()).extracting(ReleaseDiff.RouteChange::kind,
                ReleaseDiff.RouteChange::id)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CHANGED", "items.list"),
                        org.assertj.core.groups.Tuple.tuple("REMOVED", "legacy.list"),
                        org.assertj.core.groups.Tuple.tuple("ADDED", "orders.list"));
        assertThat(report.api().isEmpty()).isFalse();
        assertThat(report.newMigrations()).containsExactly("main: V2__orders.sql");
        assertThat(report.policies().added()).containsExactly("app.write");
        assertThat(report.policies().changed()).containsExactly("app.read");
        assertThat(report.policies().removed()).isEmpty();
        assertThat(report.schema().addedTables()).containsExactly("main.orders");
        assertThat(report.schema().changedTables()).containsExactly("main.items");
        assertThat(report.isEmpty()).isFalse();

        String markdown = ReleaseDiff.toMarkdown(report);
        assertThat(markdown).contains("# Release diff")
                .contains("ADDED orders.list").contains("REMOVED legacy.list")
                .contains("V2__orders.sql").contains("Added: app.write")
                .contains("Added tables: main.orders");
        assertThat(ReleaseDiff.toJson(report)).contains("\"schemaVersion\" : 1")
                .contains("orders.list");
    }

    @Test
    void identicalTreesAreEmptyAndAMissingSidecarDegradesToNullSchema(@TempDir Path base,
            @TempDir Path next) throws Exception {
        app(base, false);
        app(next, false);

        ReleaseDiff.Report same = ReleaseDiff.between(base, next);
        assertThat(same.routes()).isEmpty();
        assertThat(same.api().isEmpty()).isTrue();
        assertThat(same.newMigrations()).isEmpty();
        assertThat(same.isEmpty()).isTrue();
        assertThat(ReleaseDiff.toMarkdown(same)).contains("No changes");

        Files.delete(next.resolve(".tesseraql/docs/schema.json"));
        assertThat(ReleaseDiff.between(base, next).schema()).isNull();
    }
}
