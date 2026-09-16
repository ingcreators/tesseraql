package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a document reads the request from (docs/audit-low-leads.md slice 9): a
 * {@code header.<name>} source is a service binding's argument and nothing else's, judged from
 * the predicate the compiler refuses from ({@code RequestSourcesCompileTest} asserts the same
 * sentence); and the two body sources that never bind — {@code body.<name>} on a GET, and one
 * naming a field the route refuses as unknown — are findings where they used to be a silent
 * null. Every fixture here linted clean before.
 */
class AppLinterRequestSourcesTest {

    private static Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        return dir;
    }

    private static Path route(Path dir, String method, String recipe, String body)
            throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"),
                "select id from items where id = /* q */1\n");
        Files.writeString(dir.resolve("web/items/write.sql"),
                "insert into items (id) values (/* n */1)\n");
        Files.writeString(dir.resolve("web/items/" + method + ".yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                """.formatted(recipe, body));
        return dir;
    }

    private static List<LintFinding> of(List<LintFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    // ---- header.<name>: a service binding's argument, nothing else's ----

    @Test
    void aHeaderSourceOnAStatementIsRefusedNamingTheSlot(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: header.Cookie
                response:
                  json:
                    body:
                      rows: main.rows
                """));
        assertThat(of(findings, "TQL-YAML-1069")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("web/items/get.yml");
            assertThat(finding.line()).isNotNull();
            assertThat(finding.message()).contains("route 'items.route'")
                    .contains("sources.main params: q: 'header.Cookie'")
                    .contains("only a service: binding's params: may");
        });
    }

    @Test
    void aHeaderSourceOnAServiceBindingIsWhatTheShellsDeclare(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-html", """
                sources:
                  main:
                    service:
                      name: ops.shell.home
                      params:
                        permissions: principal.permissions
                        cookie: header.Cookie
                        csrf: header.X-CSRF-Token
                response:
                  html:
                    template: home.html
                """));
        assertThat(of(findings, "TQL-YAML-1069")).isEmpty();
    }

    @Test
    void aHeaderSourceNamingNoHeaderIsRefused(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-html", """
                sources:
                  main:
                    service:
                      name: ops.shell.home
                      params:
                        cookie: header.
                response:
                  html:
                    template: home.html
                """));
        assertThat(of(findings, "TQL-YAML-1069")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("sources.main params: cookie: 'header.'")
                .contains("names no header").contains("header.Cookie"));
    }

    /** Every slot that binds a statement is held to it, each finding naming its own slot. */
    @Test
    void aHeaderSourceOnAStepARuleOrAnEnrichmentIsRefused(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/check.sql"),
                "select 'n' as field from items where id = /* n */1\n");
        Files.writeString(dir.resolve("web/items/names.sql"),
                "select id, name from names where id = /* id */1\n");
        List<LintFinding> findings = new AppLinter().lint(route(dir, "post", "command-json", """
                input:
                  n:
                    type: integer
                validate:
                  present:
                    file: check.sql
                    params:
                      n: header.X-Count
                    field: n
                sources:
                  names:
                    sql:
                      file: list.sql
                      params:
                        q: params.n
                    enrich:
                      label:
                        sql:
                          file: names.sql
                          params:
                            id: header.X-Id
                        by: id
                        keys: [id]
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                      params:
                        n: header.X-Count
                response:
                  json:
                    body:
                      ok: true
                """));
        assertThat(of(findings, "TQL-YAML-1069")).extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message)
                        .contains("validate.present params: n: 'header.X-Count'"))
                .anySatisfy(message -> assertThat(message)
                        .contains("sources.names.enrich.label params: id: 'header.X-Id'"))
                .anySatisfy(message -> assertThat(message)
                        .contains("steps.write params: n: 'header.X-Count'"))
                .hasSize(3);
    }

    @Test
    void aToolAndAConsumerAreHeldToTheSameRule(@TempDir Path dir) throws Exception {
        app(dir);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/lookup.sql"), "select 1 as one where 1 = /* q */1\n");
        Files.writeString(dir.resolve("mcp/lookup.yml"), """
                version: tesseraql/v1
                id: lookup
                kind: tool
                recipe: query-json
                description: looks things up
                sources:
                  main:
                    sql:
                      file: lookup.sql
                      params:
                        q: header.Authorization
                """);
        Files.createDirectories(dir.resolve("consume/orders"));
        Files.writeString(dir.resolve("consume/orders/record.sql"),
                "insert into log (a) values (/* a */1)\n");
        Files.writeString(dir.resolve("consume/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.consume
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders
                steps:
                  - id: record
                    sql:
                      file: record.sql
                      mode: update
                      params:
                        a: header.X-Trace
                """);
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(of(findings, "TQL-YAML-1069")).extracting(LintFinding::message)
                .anySatisfy(message -> assertThat(message)
                        .contains("MCP tool 'lookup' sources.main params: q"))
                .anySatisfy(message -> assertThat(message)
                        .contains("consumer 'orders.consume' steps.record params: a"));
    }

    // ---- body.<name> where no body binds ----

    @Test
    void aBodySourceOnAGetIsAnErrorPointingAtTheQuery(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-json", """
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: body.q
                response:
                  json:
                    body:
                      rows: main.rows
                """));
        assertThat(of(findings, "TQL-YAML-1070")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.line()).isNotNull();
            assertThat(finding.message()).contains("sources.main params: q: 'body.q'")
                    .contains("a GET carries none").contains("read query.q");
        });
        // Not the undeclared-field finding as well: the GET arm is the whole story here.
        assertThat(of(findings, "TQL-YAML-1071")).isEmpty();
    }

    /** The export's own scalars are sources too — a variant that reads params: alone misses this. */
    @Test
    void anExportZoneReadFromTheBodyOfAGetIsTheSameError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-export", """
                input:
                  tz:
                    type: string
                export:
                  format: csv
                  timezone: body.tz
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: query.tz
                """));
        assertThat(of(findings, "TQL-YAML-1070")).singleElement().satisfies(finding -> assertThat(
                finding.message()).contains("export.timezone: 'body.tz'")
                .contains("read query.tz"));
    }

    /** A snapshot-paginated page answers the pager's POST too, so its body is not judged. */
    @Test
    void aSnapshotPaginatedGetIsNotBodiless(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "get", "query-json", """
                input:
                  q:
                    type: string
                pagination:
                  strategy: snapshot
                  size: 20
                  cap: 500
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: body.q
                response:
                  json:
                    body:
                      rows: main.rows
                """));
        assertThat(of(findings, "TQL-YAML-1070")).isEmpty();
    }

    @Test
    void anUndeclaredBodyFieldOnAPostIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "post", "command-json", """
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                      params:
                        n: body.n
                response:
                  json:
                    body:
                      ok: true
                """));
        assertThat(of(findings, "TQL-YAML-1071")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.line()).isNotNull();
            assertThat(finding.message()).contains("steps.write params: n: 'body.n'")
                    .contains("does not declare under input:")
                    .contains("mass-assignment guard").contains("declare input: n");
        });
        assertThat(of(findings, "TQL-YAML-1070")).isEmpty();
    }

    @Test
    void aDeclaredBodyFieldOnAPostIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "post", "command-json", """
                input:
                  n:
                    type: integer
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                      params:
                        n: body.n
                response:
                  json:
                    body:
                      ok: true
                """));
        assertThat(of(findings, "TQL-YAML-1071")).isEmpty();
        assertThat(of(findings, "TQL-YAML-1070")).isEmpty();
    }

    /** Under {@code unknownFields: ignore} the raw body keeps the field, so it binds. */
    @Test
    void anUndeclaredBodyFieldTheRouteIgnoresIsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(route(dir, "post", "command-json", """
                inputPolicy:
                  unknownFields: ignore
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                      params:
                        n: body.n
                response:
                  json:
                    body:
                      ok: true
                """));
        assertThat(of(findings, "TQL-YAML-1071")).isEmpty();
    }
}
