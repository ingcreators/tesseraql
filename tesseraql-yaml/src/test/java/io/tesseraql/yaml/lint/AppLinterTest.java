package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLinterTest {

    @Test
    void exampleAppHasNoErrors() {
        Path appHome = Path.of("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        List<LintFinding> findings = new AppLinter().lint(appHome);
        assertThat(findings).noneMatch(LintFinding::isError);
    }

    @Test
    void lintsMcpTools(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    policies:
                      catalog.read:
                        anyOf:
                          - role: CATALOG_READ
                """);
        Files.createDirectories(dir.resolve("mcp"));
        // A clean read tool: known recipe, defined policy, a description, an existing SQL file.
        Files.writeString(dir.resolve("mcp/find.sql"), "select 1\n");
        Files.writeString(dir.resolve("mcp/find-products.yml"), """
                version: tesseraql/v1
                id: find-products
                kind: tool
                recipe: query-json
                description: Search products.
                security:
                  auth: bearer
                  policy: catalog.read
                sql:
                  file: find.sql
                """);
        // A write tool with no policy (deny-by-default violation) and no description.
        Files.writeString(dir.resolve("mcp/delete.sql"), "delete from products where id = 1\n");
        Files.writeString(dir.resolve("mcp/purge.yml"), """
                version: tesseraql/v1
                id: purge-products
                kind: tool
                recipe: command-json
                security:
                  auth: bearer
                sql:
                  file: delete.sql
                  mode: update
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // The write tool without a policy is an error; its missing description is a warning.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-4030") && f.isError()
                && f.source().contains("purge.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1002") && !f.isError()
                && f.source().contains("purge.yml"));
        // The clean read tool raises nothing.
        assertThat(findings).noneMatch(f -> f.source().contains("find-products.yml"));
    }

    @Test
    void lintsMcpResources(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    policies:
                      catalog.read:
                        anyOf:
                          - role: CATALOG_READ
                """);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/catalog.sql"), "select 1\n");
        // A clean read-only resource: query-json, a uri, no input, a description, an existing SQL.
        Files.writeString(dir.resolve("mcp/catalog.yml"), """
                version: tesseraql/v1
                id: catalog
                kind: resource
                recipe: query-json
                uri: tesseraql://catalog
                description: The product catalog.
                security:
                  auth: bearer
                  policy: catalog.read
                sql:
                  file: catalog.sql
                """);
        // A broken resource: a write recipe, no uri, declares input, and no description.
        Files.writeString(dir.resolve("mcp/bad.sql"), "delete from products\n");
        Files.writeString(dir.resolve("mcp/bad.yml"), """
                version: tesseraql/v1
                id: bad-resource
                kind: resource
                recipe: command-json
                input:
                  q:
                    type: string
                sql:
                  file: bad.sql
                  mode: update
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1003") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1004") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1006") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1005") && !f.isError()
                && f.source().contains("bad.yml"));
        // The clean resource raises nothing.
        assertThat(findings).noneMatch(f -> f.source().contains("catalog.yml"));
    }

    @Test
    void flagsDuplicateMcpResourceUris(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/a.sql"), "select 1\n");
        for (String id : List.of("alpha", "beta")) {
            Files.writeString(dir.resolve("mcp/" + id + ".yml"), """
                    version: tesseraql/v1
                    id: %s
                    kind: resource
                    recipe: query-json
                    uri: tesseraql://shared
                    description: dup.
                    sql:
                      file: a.sql
                    """.formatted(id));
        }

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-MCP-1007") && f.isError());
    }

    @Test
    void lintsMcpUiResourcesAndToolLinks(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/board.sql"), "select 1\n");
        Files.writeString(dir.resolve("mcp/board.html"), "<section class=\"hc-card\"></section>\n");
        // A clean UI resource: query-html, a ui:// uri, no input, a description, an existing SQL.
        Files.writeString(dir.resolve("mcp/board.yml"), """
                version: tesseraql/v1
                id: board
                kind: ui
                recipe: query-html
                uri: ui://users/board
                description: A board of users.
                sql:
                  file: board.sql
                response:
                  html:
                    template: board.html
                """);
        // A clean tool that links to the UI resource.
        Files.writeString(dir.resolve("mcp/find.sql"), "select 1\n");
        Files.writeString(dir.resolve("mcp/find.yml"), """
                version: tesseraql/v1
                id: find
                kind: tool
                recipe: query-json
                description: Find users.
                ui: ui://users/board
                sql:
                  file: find.sql
                """);
        // A broken UI resource: a JSON recipe (renders no HTML), no ui:// uri, declares input,
        // and no description.
        Files.writeString(dir.resolve("mcp/bad.yml"), """
                version: tesseraql/v1
                id: bad-ui
                kind: ui
                recipe: query-json
                input:
                  q:
                    type: string
                sql:
                  file: board.sql
                """);
        // A tool linking a ui:// uri no resource declares (a dangling link).
        Files.writeString(dir.resolve("mcp/dangling.yml"), """
                version: tesseraql/v1
                id: dangling
                kind: tool
                recipe: query-json
                description: Dangling link.
                ui: ui://users/missing
                sql:
                  file: find.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1008") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1009") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1011") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1010") && !f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-MCP-1012") && f.isError()
                && f.source().contains("dangling.yml"));
        // The clean UI resource and its linking tool raise nothing.
        assertThat(findings).noneMatch(f -> f.source().contains("board.yml"));
        assertThat(findings).noneMatch(f -> f.source().contains("find.yml"));
    }

    @Test
    void reportsMissingSqlFileAndUndefinedPolicy(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: items.read
                sql:
                  file: missing.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2103") && f.isError());
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4030") && !f.isError());
    }

    @Test
    void dottedPolicyNamesResolveAsKeysOfThePoliciesMap(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    policies:
                      items.read:
                        anyOf:
                          - role: ITEMS_READ
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/search.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                security:
                  auth: bearer
                  policy: items.read
                sql:
                  file: search.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-SEC-4030"));
    }

    @Test
    void lintsNotifyDeclarationsOnRoutesAndJobs(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notifications:
                    channels:
                      member-mail:
                        type: mail
                        host: localhost
                """);
        Files.createDirectories(dir.resolve("web/members"));
        Files.writeString(dir.resolve("web/members/get.yml"), """
                version: tesseraql/v1
                id: members.search
                kind: route
                recipe: query-json
                notify:
                  declared:
                    channel: member-mail
                  channelless:
                    when: body.email !!
                    payload:
                      email: body.email
                  unknownChannel:
                    channel: missing-channel
                sql:
                  file: search.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/members/search.sql"), "select 1 as one\n");
        Files.createDirectories(dir.resolve("batch/cleanup"));
        Files.writeString(dir.resolve("batch/cleanup/job.yml"), """
                version: tesseraql/v1
                id: members.cleanup
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: broken
                    sql:
                      file: purge.sql
                    notify:
                      channel: member-mail
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // notify: on a non-command recipe.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1004") && f.isError());
        // A notification without a channel, and a job step declaring both sql: and notify:.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2004") && f.isError())
                .hasSize(2);
        // A malformed when: guard.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2101") && f.isError());
        // An undeclared channel is a warning: another environment's config may declare it.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1102") && !f.isError()
                && f.message().contains("missing-channel"));
    }

    @Test
    void lintsHttpCallStepsAgainstTheEgressAllowList(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notifications:
                    channels:
                      member-mail:
                        type: mail
                        host: localhost
                  http:
                    outbound:
                      allowedHosts:
                        - api.partner.example
                        - "*.internal.example"
                      credentials:
                        partner:
                          type: bearer
                          token: ${secret.env.PARTNER_TOKEN}
                """);
        Files.createDirectories(dir.resolve("batch/sync"));
        Files.writeString(dir.resolve("batch/sync/job.yml"), """
                version: tesseraql/v1
                id: orders.sync
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: allowed
                    http-call:
                      url: https://api.partner.example/v1/orders
                      credential: partner
                  - id: subdomain
                    http-call:
                      url: https://eu.internal.example/v1/rates
                  - id: denied
                    http-call:
                      url: https://evil.example/v1/exfil
                  - id: relative
                    http-call:
                      method: GET
                  - id: badcred
                    http-call:
                      url: https://api.partner.example/v1/y
                      credential: ghost
                  - id: ambiguous
                    http-call:
                      url: https://api.partner.example/v1/z
                    notify:
                      channel: member-mail
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // Only the off-allow-list host is denied; exact and *.wildcard hosts pass cleanly.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-SEC-4070") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("evil.example"));
        // A step with no absolute url.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-SEC-4071") && f.isError())
                .hasSize(1);
        // An undeclared credential is a warning, not an error.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4072") && !f.isError()
                && f.message().contains("ghost"));
        // A step declaring two kinds at once.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2004") && f.isError()
                && f.message().contains("ambiguous"));
    }

    @Test
    void nudgesVersionPredicateOnExpectedRowCountUpdates(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  bump:
                    file: bump.sql
                    mode: update
                    expect:
                      rows: 1
                """, "update orders set status = /* s */'X' where id = /* id */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2104") && !f.isError());
    }

    @Test
    void nudgesExpectOnVersionPredicateUpdates(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                sql:
                  file: bump.sql
                  mode: update
                """, "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2105") && !f.isError());
    }

    @Test
    void quietWhenUpdateDeclaresExpectAndVersionPredicate(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                sql:
                  file: bump.sql
                  mode: update
                  expect:
                    rows: 1
                """, "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2104") || f.code().equals("TQL-SQL-2105"));
    }

    @Test
    void reportsMissingStepSqlFile(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  header:
                    file: nope.sql
                """, null);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2103") && f.isError()
                        && f.message().contains("nope.sql"));
    }

    @Test
    void reportsMissingValidationSqlFileAndMisshapenRules(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                validate:
                  uniqueName:
                    file: missing-check.sql
                    field: name
                  shapeless:
                    field: name
                  fieldless:
                    rule: body.endDate >= body.startDate
                sql:
                  file: bump.sql
                  mode: update
                """, "insert into t (a) values (/* a */1)\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2103") && f.isError()
                && f.message().contains("missing-check.sql"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2003") && f.isError()
                && f.message().contains("'shapeless'")
                && f.message().contains("exactly one of rule: or file:"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2003") && f.isError()
                && f.message().contains("'fieldless'") && f.message().contains("field:"));
    }

    @Test
    void reportsWritingValidationSqlAndMalformedExpressions(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                validate:
                  writes:
                    file: bump.sql
                    field: name
                  broken:
                    rule: 'body.endDate >='
                    field: endDate
                sql:
                  file: bump.sql
                  mode: update
                """, "update t set a = /* a */1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2003") && f.isError()
                && f.message().contains("must be a SELECT"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2101") && f.isError()
                && f.message().contains("'broken'"));
    }

    @Test
    void reportsValidateOnANonCommandRecipe(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                validate:
                  positive:
                    rule: query.limit > 0
                    field: limit
                sql:
                  file: search.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/items/search.sql"),
                "select * from t where a = /* limit */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1003") && f.isError()
                        && f.message().contains("command-json"));
    }

    @Test
    void quietOnAWellFormedValidateBlock(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                validate:
                  uniqueName:
                    file: check-name.sql
                    params:
                      name: body.name
                    field: name
                    code: duplicate
                  dateOrder:
                    when: body.endDate != null
                    rule: body.endDate >= body.startDate
                    field: endDate
                sql:
                  file: bump.sql
                  mode: update
                """, "insert into t (a) values (/* a */1)\n");
        Files.writeString(dir.resolve("web/api/orders/check-name.sql"),
                "-- uniqueness check\nselect 'name' as field from t where a = /* name */'x'\n");

        assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
    }

    private static void writeCommandRoute(Path dir, String bindingYaml, String bumpSql)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/orders"));
        Files.writeString(dir.resolve("web/api/orders/post.yml"), """
                version: tesseraql/v1
                id: orders.cmd
                kind: route
                recipe: command-json
                """ + bindingYaml + """
                response:
                  json:
                    body:
                      ok: sql.affectedRows
                """);
        if (bumpSql != null) {
            Files.writeString(dir.resolve("web/api/orders/bump.sql"), bumpSql);
        }
    }

    @Test
    void reportsUnknownRecipe(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: bogus-recipe
                sql:
                  contract: identity.list-users
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1002") && f.isError());
    }

    @Test
    void warnsOnSharedSchemaRouteWithoutTenantPredicate(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), """
                server:
                  port: 0
                tenancy:
                  enabled: true
                  mode: shared-schema
                """);
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");

        Files.createDirectories(dir.resolve("web/api/leaky"));
        Files.writeString(dir.resolve("web/api/leaky/get.yml"), """
                version: tesseraql/v1
                id: leaky.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: list.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/leaky/list.sql"), "select id, name from items\n");

        Files.createDirectories(dir.resolve("web/api/scoped"));
        Files.writeString(dir.resolve("web/api/scoped/get.yml"), """
                version: tesseraql/v1
                id: scoped.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: list.sql
                  params:
                    tenant_id: tenant.id
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/scoped/list.sql"),
                "select id, name from items where tenant_id = /* tenant_id */ 'x'\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-TENANT-3001")
                && !f.isError() && f.source().contains("leaky"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-TENANT-3001")
                && f.source().contains("scoped"));
    }

    @Test
    void noTenantWarningWhenTenancyDisabled(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/leaky"));
        Files.writeString(dir.resolve("web/api/leaky/get.yml"), """
                version: tesseraql/v1
                id: leaky.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: list.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/leaky/list.sql"), "select id, name from items\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-TENANT-3001"));
    }

    @Test
    void lintsPdfExportDeclarations(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/users/print"));
        Files.writeString(dir.resolve("web/api/users/print/print.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/users/print/get.yml"), """
                version: tesseraql/v1
                id: users.print
                kind: route
                recipe: query-export
                sql:
                  file: print.sql
                export:
                  format: pdf
                  sheet: data
                  template: print.xlsx
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // sheet: is a workbook option; the template must be .html.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1005") && f.isError());
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1006") && f.isError());
    }

    @Test
    void aMissingPdfTemplateIsAnErrorAndACleanPdfExportPasses(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/users/print"));
        Files.writeString(dir.resolve("web/api/users/print/print.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/users/print/get.yml"), """
                version: tesseraql/v1
                id: users.print
                kind: route
                recipe: query-export
                sql:
                  file: print.sql
                export:
                  format: pdf
                  template: print.html
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1006") && f.isError());

        Files.writeString(dir.resolve("web/api/users/print/print.html"),
                "<html><body>ok</body></html>\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().startsWith("TQL-YAML-100")
                        && (f.code().endsWith("5") || f.code().endsWith("6")));
    }

    private static List<LintFinding> lintWithConfig(Path dir, String securityYaml)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """ + securityYaml);
        return new AppLinter().lint(dir);
    }

    @Test
    void flagsRs256JwtWithoutKeySource(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4040") && f.isError());
    }

    @Test
    void flagsRs256JwtWithConflictingKeySources(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                      publicKey: pem
                      jwksUri: https://idp.example.com/jwks
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4041") && f.isError());
    }

    @Test
    void flagsAlgorithmConfusion(@TempDir Path dir) throws Exception {
        // HS256 with RS256 key material, and RS256 with an HS256 secret, both raise TQL-SEC-4042.
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: HS256
                      secret: s
                      jwksUri: https://idp.example.com/jwks
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4042") && f.isError());
    }

    @Test
    void flagsRs256JwtWithSecret(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                      publicKey: pem
                      secret: s
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4042") && f.isError());
    }

    @Test
    void flagsUnsupportedJwtAlgorithm(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: none
                      secret: s
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4043") && f.isError());
    }

    @Test
    void acceptsValidRs256JwksConfig(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                      jwksUri: https://idp.example.com/jwks
                """)).noneMatch(f -> f.code().startsWith("TQL-SEC-404") && f.isError());
    }

    @Test
    void flagsApiKeyClientWithoutSecretHash(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    apiKeys:
                      clients:
                        billing:
                          roles: [SERVICE]
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4045") && f.isError());
    }

    @Test
    void warnsApiKeyClientWithoutGrants(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    apiKeys:
                      clients:
                        billing:
                          secretHash: abc123
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4046") && !f.isError());
    }

    @Test
    void flagsOidcWithoutDiscoveryUri(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  oidc:
                    enabled: true
                    clientId: app
                    redirectUri: https://app.example.com/_tesseraql/oidc/callback
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4050") && f.isError());
    }

    @Test
    void flagsOidcNonHttpsDiscoveryUri(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  oidc:
                    enabled: true
                    discoveryUri: http://idp.example.com/.well-known/openid-configuration
                    clientId: app
                    redirectUri: https://app.example.com/_tesseraql/oidc/callback
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4051") && f.isError());
    }

    @Test
    void flagsOidcWithoutClientIdOrRedirectUri(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  oidc:
                    enabled: true
                    discoveryUri: https://idp.example.com/.well-known/openid-configuration
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4052") && f.isError());
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4053") && f.isError());
    }

    @Test
    void acceptsValidOidcConfig(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  oidc:
                    enabled: true
                    discoveryUri: https://idp.example.com/.well-known/openid-configuration
                    clientId: app
                    redirectUri: https://app.example.com/_tesseraql/oidc/callback
                """)).noneMatch(f -> f.code().startsWith("TQL-SEC-405") && f.isError());
    }

    @Test
    void flagsApiKeyRouteWithoutApiKeyConfig(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/api/things"));
        Files.writeString(dir.resolve("web/api/things/search.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/things/get.yml"), """
                version: tesseraql/v1
                id: things.search
                kind: route
                recipe: query-json
                security:
                  auth: apiKey
                sql:
                  file: search.sql
                  mode: query
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4044") && f.isError());
    }

    @Test
    void flagsMtlsWithoutForwardedHeader(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      trustBundle: ca-pem
                      clients:
                        billing:
                          subjectDn: "CN=billing,O=Acme"
                          roles: [SERVICE]
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4061") && f.isError());
    }

    @Test
    void flagsMtlsClientWithNoOrMultipleMatchers(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        none:
                          roles: [SERVICE]
                        both:
                          subjectDn: "CN=billing,O=Acme"
                          sha256: "ab:cd"
                          roles: [SERVICE]
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4062") && f.isError()
                && f.message().contains("'none'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4063") && f.isError()
                && f.message().contains("'both'"));
    }

    @Test
    void warnsMtlsClientWithoutGrants(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        billing:
                          subjectDn: "CN=billing,O=Acme"
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4064") && !f.isError());
    }

    @Test
    void warnsMtlsWithoutTrustBundle(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      clients:
                        billing:
                          subjectDn: "CN=billing,O=Acme"
                          roles: [SERVICE]
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4065") && !f.isError());
    }

    @Test
    void acceptsValidMtlsConfig(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        billing:
                          subjectDn: "CN=billing,O=Acme"
                          roles: [SERVICE]
                """)).noneMatch(f -> f.code().startsWith("TQL-SEC-406") && f.isError());
    }

    @Test
    void flagsMtlsRouteWithoutMtlsConfig(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/api/things"));
        Files.writeString(dir.resolve("web/api/things/search.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/things/get.yml"), """
                version: tesseraql/v1
                id: things.search
                kind: route
                recipe: query-json
                security:
                  auth: mtls
                sql:
                  file: search.sql
                  mode: query
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4060") && f.isError());
    }
}
