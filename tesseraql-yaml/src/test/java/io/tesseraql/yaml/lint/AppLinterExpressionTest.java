package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expression positions lint judges (docs/audit-low-leads.md slice 11): a 2-way SQL file that
 * does not parse (G9) and the three manifest guards that were parsed only at boot — a response
 * {@code headersWhen:}, a step {@code when:}, a notification {@code recipient:} (G10). Each used
 * to lint clean, pass admission, and fail at the first request or the boot with a bare sentence.
 */
class AppLinterExpressionTest {

    private static List<LintFinding> expressionFindings(List<LintFinding> findings) {
        return findings.stream()
                .filter(f -> f.code().equals("TQL-SQL-2101") || f.code().equals("TQL-SQL-2102"))
                .toList();
    }

    private static void writeQueryRoute(Path dir, String sql) throws Exception {
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/search.sql"), sql);
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: search.sql
                  count:
                    sql:
                      file: search.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
    }

    @Test
    void aDirectiveThatDoesNotParseIsOneErrorNamingTheFileAndTheLine(@TempDir Path dir)
            throws Exception {
        // Nothing loads a query route's SQL before its first request, so lint is the only
        // static gate; it swallowed the parse failure and every SQL lint skipped the file.
        writeQueryRoute(dir, "select * from items where 1 = 1\n"
                + "/*%if q > */ and name = /* q */'x' /*%end*/\n");

        List<LintFinding> findings = expressionFindings(new AppLinter().lint(dir));

        // Two sources read the file; the memo reports it once.
        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SQL-2101");
            assertThat(finding.isError()).isTrue();
            assertThat(finding.source()).isEqualTo("web/api/items/search.sql");
            assertThat(finding.line()).isEqualTo(2);
            assertThat(finding.message()).contains("does not parse")
                    .contains("Unexpected end of expression in 'q >'");
        });
    }

    @Test
    void anUnknownFunctionInADirectiveIsAnError(@TempDir Path dir) throws Exception {
        // admission.md's declarative-only promise: a custom function the lint classpath does
        // not carry fails the gate as an unknown function — now on a SQL directive too.
        writeQueryRoute(dir, "select * from items where 1 = 1\n"
                + "/*%if nope(q) */ and name = /* q */'x' /*%end*/\n");

        assertThat(expressionFindings(new AppLinter().lint(dir))).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-SQL-2101");
                    assertThat(finding.line()).isEqualTo(2);
                    assertThat(finding.message()).contains("Unknown function 'nope()'");
                });
    }

    @Test
    void anUnterminatedDirectiveIsTheParsersOwnCode(@TempDir Path dir) throws Exception {
        writeQueryRoute(dir, "select * from items\n/*%if q != null */ where name = /* q */'x'\n");

        assertThat(expressionFindings(new AppLinter().lint(dir))).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-SQL-2102");
                    assertThat(finding.source()).isEqualTo("web/api/items/search.sql");
                    assertThat(finding.line()).isNotNull();
                });
    }

    @Test
    void aBrokenDirectiveNoLongerHidesTheFilesOtherFindings(@TempDir Path dir) throws Exception {
        // The injection lint skipped a file that did not parse: one typo in a directive
        // silenced an ERROR on the same file. The file is refused, so the author fixes the
        // directive and the second finding fires; what matters is that the first fires at all.
        writeQueryRoute(dir, "select * from items where 1 = 1\n"
                + "/*%if q > */ and name = /* q */'x' /*%end*/\n"
                + "order by /*# {q} */name\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2101") && f.isError());
    }

    @Test
    void aWellFormedFileIsClean(@TempDir Path dir) throws Exception {
        writeQueryRoute(dir, "select * from items where 1 = 1\n"
                + "/*%if q != null && q != '' */ and name = /* q */'x' /*%end*/\n");

        assertThat(expressionFindings(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aBrokenHeadersWhenGuardIsAnErrorOnBothResponseArms(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/search.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                input:
                  limit:
                    type: integer
                sources:
                  main:
                    sql:
                      file: search.sql
                response:
                  json:
                    body:
                      data: main.rows
                    headers:
                      X-Probe: "1"
                      X-Fine: "1"
                    headersWhen:
                      X-Probe: "params.limit >"
                      X-Fine: "params.limit > 10"
                """);
        Files.createDirectories(dir.resolve("web/home"));
        Files.writeString(dir.resolve("web/home/home.sql"), "select 1 as one\n");
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/home.html"), "<main></main>\n");
        Files.writeString(dir.resolve("web/home/get.yml"), """
                version: tesseraql/v1
                id: home.page
                kind: route
                recipe: query-html
                security:
                  auth: browser
                sources:
                  main:
                    sql:
                      file: home.sql
                response:
                  html:
                    template: home.html
                    headers:
                      X-Page: "1"
                    headersWhen:
                      X-Page: "main.rowCount =="
                """);

        List<LintFinding> findings = expressionFindings(new AppLinter().lint(dir));

        assertThat(findings).hasSize(2);
        assertThat(findings).anyMatch(f -> f.source().equals("web/api/items/get.yml")
                && f.isError() && f.message().contains("headersWhen: the guard on 'X-Probe'")
                && f.line() != null && f.line() == 20);
        assertThat(findings).anyMatch(f -> f.source().equals("web/home/get.yml")
                && f.message().contains("headersWhen: the guard on 'X-Page'"));
    }

    @Test
    void aBrokenStepWhenIsAnErrorOnARouteAndATool(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/insert.sql"),
                "insert into items (name) values (/* body.name */'x')\n");
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                  quantity:
                    type: integer
                steps:
                  - id: insert
                    sql:
                      file: insert.sql
                  - id: restock
                    when: "body.quantity >"
                    sql:
                      file: insert.sql
                response:
                  json:
                    body:
                      ok: true
                """);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/insert.sql"),
                "insert into items (name) values (/* params.name */'x')\n");
        Files.writeString(dir.resolve("mcp/create-item.yml"), """
                version: tesseraql/v1
                id: create_item
                kind: tool
                description: Creates an item
                recipe: command-json
                security:
                  policy: items.write
                input:
                  name:
                    type: string
                steps:
                  - id: insert
                    when: "params.name !!"
                    sql:
                      file: insert.sql
                """);

        List<LintFinding> findings = expressionFindings(new AppLinter().lint(dir));

        assertThat(findings).anyMatch(f -> f.source().equals("web/api/items/post.yml")
                && f.isError()
                && f.message().contains("Step 'restock' has a malformed when: expression")
                && f.line() != null && f.line() == 15);
        assertThat(findings).anyMatch(f -> f.source().equals("mcp/create-item.yml")
                && f.message().contains("Step 'insert' has a malformed when: expression"));
    }

    @Test
    void aBrokenRecipientIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notifications:
                    channels:
                      audit-mail:
                        type: mail
                        host: localhost
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/insert.sql"),
                "insert into items (name) values (/* body.name */'x')\n");
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name:
                    type: string
                steps:
                  - id: insert
                    sql:
                      file: insert.sql
                notify:
                  audit:
                    channel: audit-mail
                    when: "body.name != null"
                    recipient: "principal.loginId +"
                  fine:
                    channel: audit-mail
                    recipient: "principal.loginId + '@corp.example'"
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = expressionFindings(new AppLinter().lint(dir));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.isError()).isTrue();
            assertThat(finding.source()).isEqualTo("web/api/items/post.yml");
            assertThat(finding.message())
                    .contains("Notification 'audit' has a malformed recipient: expression");
        });
    }
}
