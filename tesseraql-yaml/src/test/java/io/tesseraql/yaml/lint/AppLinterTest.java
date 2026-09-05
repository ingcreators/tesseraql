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
    void acceptsARelativeAppHome() {
        // The documented CLI form is `tesseraql lint --app .` — the linter must not
        // trip over relativizing the loader's absolute source paths against it.
        List<LintFinding> findings = new AppLinter()
                .lint(Path.of("..", "examples", "user-admin-app"));
        assertThat(findings).noneMatch(LintFinding::isError);
    }

    @Test
    void customExpressionFunctionsLintCleanOnceInstalled(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/members"));
        Files.writeString(dir.resolve("web/members/register.sql"),
                "insert into members (kana) values (/* kana */'カナ')\n");
        Files.writeString(dir.resolve("web/members/post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                validate:
                  kanaName:
                    rule: isKatakana(body.kana)
                    field: kana
                    code: not-kana
                steps:
                  - id: main
                    sql:
                      file: register.sql
                      mode: update
                response:
                  json:
                    status: 201
                """);

        // Without the function installed, the rule's call site is an unknown-function error.
        assertThat(new AppLinter().lint(dir))
                .anyMatch(finding -> finding.isError()
                        && finding.message().contains("Unknown function 'isKatakana()'"));

        // Installed (as `tesseraql lint` does from the modules classpath), the same app is clean.
        io.tesseraql.core.expr.ExpressionFunctions.install(List.of(
                new io.tesseraql.core.expr.ExpressionFunction() {
                    @Override
                    public String name() {
                        return "isKatakana";
                    }

                    @Override
                    public int arity() {
                        return 1;
                    }

                    @Override
                    public Object apply(List<Object> args) {
                        return args.get(0) != null
                                && String.valueOf(args.get(0)).matches("[\\u30A0-\\u30FF]+");
                    }
                }));
        try {
            assertThat(new AppLinter().lint(dir)).noneMatch(LintFinding::isError);
        } finally {
            io.tesseraql.core.expr.ExpressionFunctions.reset();
        }
    }

    @Test
    void lintsMcpTools(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    # The tools below declare auth: bearer, so the app needs a verifier
                    # (TQL-SEC-4047).
                    jwt:
                      secret: dev-only-secret-change-me-in-production
                      audience: https://app.example.com
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
                sources:
                  main:
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
                steps:
                  - id: main
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
    void flagsAnEmbeddedVariableNotBackedByAnEnumInput(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    policies:
                      app.read:
                        anyOf:
                          - role: READ
                """);
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/search.sql"),
                "select 1 from items t\n/*# order by t.{sort} */\n");
        String route = """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                input:
                  sort:
                    type: string
                %s
                security:
                  auth: browser
                  policy: app.read
                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                      params:
                        sort: query.sort
                response:
                  json:
                    body:
                      rows: main.rows
                """;

        // No enum on the interpolated input: the embedded variable is an injection vector.
        Files.writeString(dir.resolve("web/items/get.yml"), route.formatted(""));
        assertThat(new AppLinter().lint(dir)).anyMatch(f -> f.code().equals("TQL-SQL-2109")
                && f.isError() && f.source().contains("get.yml"));

        // An enum allowlist clears it.
        Files.writeString(dir.resolve("web/items/get.yml"),
                route.formatted("    enum: [id, name]"));
        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2109"));
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
                sources:
                  main:
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
                steps:
                  - id: main
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
                    sources:
                      main:
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
                sources:
                  main:
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
                sources:
                  main:
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
                sources:
                  main:
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
                sources:
                  main:
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
        // The clean UI resource and its linking tool raise nothing in this family. Scoped to
        // TQL-MCP-10xx rather than asserting silence outright: find.yml is a read tool that
        // declares no security:, which TQL-MCP-4261 reports on purpose, and a blanket noneMatch
        // would make every future lint look like a regression here.
        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-MCP-10")
                && f.source().contains("board.yml"));
        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-MCP-10")
                && f.source().contains("find.yml"));
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
                sources:
                  main:
                    sql:
                      file: missing.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2103") && f.isError());
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4030") && !f.isError());
    }

    @Test
    void rejectsAMisspelledRouteLocalCsrfValue(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  csrf: requred
                steps:
                  - id: main
                    text: insert into items(name) values (:name)
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4132") && f.isError()
                && f.message().contains("requred"));
    }

    @Test
    void rejectsAMisspelledInputPolicyValue(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                inputPolicy:
                  unknownFields: Reject
                steps:
                  - id: main
                    text: insert into items(name) values (:name)
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // Exactly one finding: lintInputPolicy used to be called twice from lintRoute, so every
        // violation was reported twice. No rule may double-report — the same invariant, app-wide.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2006") && f.isError()
                        && f.message().contains("mass-assignment"))
                .hasSize(1);
        assertThat(findings)
                .as("no lint finding is reported twice")
                .doesNotHaveDuplicates();
    }

    @Test
    void reportsAMisnamedRouteFileAndAYamlExtension(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        // A route file misnamed (route.yml, not <method>.yml) and one with a .yaml extension.
        Files.writeString(dir.resolve("web/api/items/route.yml"), """
                version: tesseraql/v1
                id: items.stray
                kind: route
                recipe: query-json
                """);
        Files.writeString(dir.resolve("web/api/items/get.yaml"), """
                version: tesseraql/v1
                id: items.doublea
                kind: route
                recipe: query-json
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-APP-4205")
                && f.message().contains("route.yml"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-APP-4205")
                && f.message().contains("get.yaml") && f.message().contains(".yaml"));
    }

    @Test
    void reportsANestedSharedDefinitionFileAndAcceptsRootAndViews(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        // domains/ loads non-recursively: a nested file is silently dropped.
        Files.createDirectories(dir.resolve("domains/hr"));
        Files.writeString(dir.resolve("domains/hr/fields.yml"), "version: tesseraql/v1\n");
        // A well-named view and a proper HTTP-method route must NOT be flagged.
        Files.createDirectories(dir.resolve("web/tickets"));
        Files.writeString(dir.resolve("web/tickets/get.yml"), """
                version: tesseraql/v1
                id: tickets.list
                kind: route
                recipe: query-json
                """);
        Files.writeString(dir.resolve("web/tickets/list.view.yml"), """
                version: tesseraql/v1
                id: tickets.view
                kind: view
                recipe: table
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-APP-4205")
                && f.message().contains("domains/hr/fields.yml")
                && f.message().contains("non-recursively"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-APP-4205")
                && (f.message().contains("get.yml") || f.message().contains("list.view.yml")));
    }

    @Test
    void flagsAnUnknownTopLevelRouteKeyAsAWarning(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        // `securty:` is a typo for security:, silently dropped so the route loses its auth block.
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                securty:
                  auth: bearer
                sources:
                  main:
                    text: select 1
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043") && !f.isError()
                && f.message().contains("securty"));
    }

    /**
     * Keyset pagination is refused on a contract binding rather than published.
     *
     * <p>It was accepted and silently wrong. The page binder mints offset 0 for every keyset
     * request, and the {@code after} predicate that would advance the cursor lives in the author's
     * own statement — which a bundled contract does not have. So a {@code next} link would have
     * advertised an endless chain of identical pages. Offset pagination, the half that works, is
     * unaffected.
     */
    @Test
    void refusesKeysetPaginationOnAContractBinding(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/users"));
        Files.writeString(dir.resolve("web/api/users/get.yml"), """
                version: tesseraql/v1
                id: users.list
                kind: route
                recipe: query-json
                input:
                  page:
                    type: integer
                    required: false
                pagination:
                  strategy: keyset
                  by: user_id
                  size: 20
                sources:
                  main:
                    contract:
                      name: identity.list-users
                response:
                  json:
                    body:
                      data: main.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1016") && f.isError()
                && f.message().contains("keyset")
                && f.message().contains("contract:"));
    }

    /** Offset pagination on the same binding is untouched: it is the half a contract can honour. */
    @Test
    void allowsOffsetPaginationOnAContractBinding(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/users"));
        Files.writeString(dir.resolve("web/api/users/get.yml"), """
                version: tesseraql/v1
                id: users.list
                kind: route
                recipe: query-json
                input:
                  page:
                    type: integer
                    required: false
                pagination:
                  strategy: offset
                  size: 20
                sources:
                  main:
                    contract:
                      name: identity.list-users
                response:
                  json:
                    body:
                      data: main.rows
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1016"));
    }

    @Test
    void flagsARenamedTopLevelKeyWithItsReplacement(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        // `page:` was renamed to `pagination:` before v1.
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                page:
                  size: 20
                sources:
                  main:
                    text: select 1
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1044") && f.isError()
                && f.message().contains("page") && f.message().contains("pagination"));
    }

    /**
     * The extraction moved out of {@code export:} and into {@code sources:}, and the block still
     * accepted the old key — {@code @JsonIgnoreProperties(ignoreUnknown)} on the record, no
     * properties in the schema, and an unknown-key lint that stopped at the document's own keys.
     * The result was an export that wrote an empty file with nothing said at build time, which
     * is exactly what the documentation went on teaching.
     */
    @Test
    void flagsAnExtractionLeftInsideTheExportBlock(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items/export"));
        Files.writeString(dir.resolve("web/api/items/export/get.yml"), """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                export:
                  format: csv
                  sql:
                    file: extract.sql
                  queries:
                    header: { file: header.sql }
                sources:
                  main:
                    text: select 1
                """);
        Files.writeString(dir.resolve("web/api/items/export/extract.sql"), "select 1\n;\n");
        Files.writeString(dir.resolve("web/api/items/export/header.sql"), "select 1\n;\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1044") && f.isError()
                && f.message().contains("export.sql") && f.message().contains("sources"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1044") && f.isError()
                && f.message().contains("export.queries"));
    }

    /** A key nobody moved is still the warning it is at the top level, named by its path. */
    @Test
    void flagsAnUnknownKeyInsideAFixedShapeBlock(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items/export"));
        Files.writeString(dir.resolve("web/api/items/export/get.yml"), """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                export:
                  format: csv
                  filenam: items.csv
                sources:
                  main:
                    text: select 1
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043") && !f.isError()
                && f.message().contains("export.filenam"));
    }

    /**
     * A pipeline step's blocks are checked the way the document's are — and the step's own keys
     * come from its {@code @JsonCreator}, not its components: it holds a folded {@code Binding},
     * so reading components would have called {@code when:}, {@code http:} and {@code enrich:}
     * unknown on a step that legally carries all three.
     */
    @Test
    void flagsAnUnknownKeyInAPipelineStepsBlocks(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("batch/reports"));
        Files.writeString(dir.resolve("batch/reports/daily.yml"), """
                version: tesseraql/v1
                id: reports.daily
                kind: job
                recipe: batch-pipeline
                trigger:
                  schedule:
                    cron: "0 0 4 * * ?"
                pipeline:
                  - id: report
                    when: params.run
                    sql: { file: report.sql, mode: query }
                    export:
                      format: csv
                    push:
                      transport: local
                      pth: outbox/reports
                  - id: announce
                    notify:
                      channel: reports
                      attatch: steps.report.transferId
                """);
        Files.writeString(dir.resolve("batch/reports/report.sql"), "select 1\n;\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.message().contains("step 'report' push.pth"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1043")
                && f.message().contains("step 'announce' notify.attatch"));
        // `when:` is authored on the step and folded into its binding — a legal key, not a finding.
        assertThat(findings).noneMatch(f -> f.message().contains("'when"));
    }

    @Test
    void flagsARenamedDecisionSourceKeyColumn(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("decisions"));
        // `source.id:` was renamed to keyColumn:; the drop is masked by the "id" default.
        Files.writeString(dir.resolve("decisions/pricing.yml"), """
                version: tesseraql/v1
                decisions:
                  pricing:
                    hitPolicy: unique
                    inputs:
                      tier: { type: string }
                    outputs:
                      rate: { type: number }
                    source:
                      table: pricing_rules
                      id: rule_key
                      match:
                        tier: { eq: tier_col }
                      outputs:
                        rate: rate_col
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-DECISION-4718") && f.isError()
                && f.message().contains("keyColumn"));
    }

    @Test
    void reportsAMissingQueryFileAndANegativeQueryTimeout(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/main.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: main.sql
                  detail:
                    sql:
                      file: recnt.sql
                      timeoutSeconds: -1
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2103") && f.isError()
                && f.message().contains("detail") && f.message().contains("recnt.sql"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1021") && f.isError()
                && f.message().contains("detail"));
    }

    @Test
    void reportsAnUnresolvableMessageKeyEvenWithNoMessagesDirectory(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/main.sql"), "select 1\n");
        // A validate rule declares a message: key but the app has no messages/ catalog at all.
        Files.writeString(dir.resolve("web/api/items/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                validate:
                  qty:
                    rule: body.qty > 0
                    field: qty
                    code: too-small
                    message: items.qty.tooSmall
                steps:
                  - id: main
                    sql:
                      file: main.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: true
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2005") && !f.isError()
                && f.message().contains("items.qty.tooSmall"));
    }

    @Test
    void flagsAnEmbeddedVariableInjectionOnAnMcpTool(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    policies:
                      catalog.read:
                        anyOf:
                          - role: READ
                """);
        Files.createDirectories(dir.resolve("mcp"));
        // A tool's SQL is driven by LLM arguments — the highest-risk injection surface, which the
        // guard used to skip. An embedded {sort} with no enum allowlist must be flagged.
        Files.writeString(dir.resolve("mcp/search.sql"),
                "select 1 from items t\n/*# order by t.{sort} */\n");
        Files.writeString(dir.resolve("mcp/search.yml"), """
                version: tesseraql/v1
                id: search
                kind: tool
                recipe: query-json
                description: Search items.
                input:
                  sort:
                    type: string
                security:
                  auth: bearer
                  policy: catalog.read
                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                      params:
                        sort: query.sort
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2109") && f.isError()
                && f.source().contains("search.yml"));
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
                sources:
                  main:
                    sql:
                      file: search.sql
                response:
                  json:
                    body:
                      data: main.rows
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
                sources:
                  main:
                    sql:
                      file: search.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(dir.resolve("web/members/search.sql"), "select 1 as one\n");
        Files.createDirectories(dir.resolve("batch/cleanup"));
        Files.writeString(dir.resolve("batch/cleanup/job.yml"), """
                version: tesseraql/v1
                id: members.cleanup
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: purge
                    sql:
                      file: purge.sql
                      notify:
                        channel: member-mail
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // notify: on a non-command recipe.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1004") && f.isError());
        // A notification without a channel. A step that reports what it wrote is a binding
        // plus an output block, so it draws nothing (docs/unified-sources.md decision 12).
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2004") && f.isError())
                .hasSize(1);
        // A malformed when: guard.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2101") && f.isError());
        // An undeclared channel is a warning: another environment's config may declare it.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1102") && !f.isError()
                && f.message().contains("missing-channel"));
    }

    /** Roadmap Phase 49: an inbox-channel notification must name its recipient. */
    @Test
    void anInboxNotificationWithoutARecipientFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notifications:
                    channels:
                      approvals:
                        type: inbox
                        title: "hello"
                """);
        Files.createDirectories(dir.resolve("web/decide"));
        Files.writeString(dir.resolve("web/decide/post.yml"), """
                version: tesseraql/v1
                id: decide
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                notify:
                  addressed:
                    channel: approvals
                    recipient: principal.subject
                  unaddressed:
                    channel: approvals
                steps:
                  - id: main
                    sql:
                      file: decide.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: notify
                """);
        Files.writeString(dir.resolve("web/decide/decide.sql"), "update t set x = 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1034"))
                .hasSize(1)
                .allMatch(f -> f.isError() && f.message().contains("unaddressed"));
    }

    /** Attachments ride mail channels only (docs/analytics-experience.md). */
    @Test
    void anAttachmentOnANonMailChannelFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  notifications:
                    channels:
                      audit:
                        type: webhook
                        url: https://hooks.example/x
                      reports:
                        type: mail
                        host: smtp.example
                        from: noreply@example.com
                        to: ops@example.com
                        template: templates/mail/report.txt
                """);
        Files.createDirectories(dir.resolve("batch/report"));
        Files.writeString(dir.resolve("batch/report/job.yml"), """
                version: tesseraql/v1
                id: report.daily
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: extract
                    sql:
                      file: report.sql
                      mode: query
                    export:
                      format: csv
                  - id: hooked
                    notify:
                      channel: audit
                      attach: steps.extract.transferId
                  - id: mailed
                    notify:
                      channel: reports
                      attach: steps.extract.transferId
                """);
        Files.writeString(dir.resolve("batch/report/report.sql"), "select 1 as one\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2004"))
                .hasSize(1)
                .allMatch(f -> f.isError()
                        && f.message().contains("attachments ride mail channels only"));
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
                    http:
                      url: https://api.partner.example/v1/orders
                      credential: partner
                  - id: subdomain
                    http:
                      url: https://eu.internal.example/v1/rates
                  - id: denied
                    http:
                      url: https://evil.example/v1/exfil
                  - id: relative
                    http:
                      method: GET
                  - id: badcred
                    http:
                      url: https://api.partner.example/v1/y
                      credential: ghost
                  - id: reported
                    http:
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
        // A call reporting its outcome is a binding plus an output block, not a conflict
        // (docs/unified-sources.md decision 12).
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-2004")
                && f.message().contains("reported"));
    }

    @Test
    void aStepsEnrichNeedsRowsToFoldInto(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("batch/sync"));
        Files.writeString(dir.resolve("batch/sync/orders.sql"), "select id, code from orders\n");
        Files.writeString(dir.resolve("batch/sync/close.sql"), "update orders set closed = 1\n");
        Files.writeString(dir.resolve("batch/sync/names.sql"),
                "select code, name from codes where code in /* keys */('A')\n");
        Files.writeString(dir.resolve("batch/sync/job.yml"), """
                version: tesseraql/v1
                id: orders.sync
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: read
                    sql:
                      file: orders.sql
                      mode: query
                    enrich:
                      name:
                        on: { code: code }
                        sql:
                          file: names.sql
                        merge: [name]
                  - id: close
                    sql:
                      file: close.sql
                      mode: update
                    enrich:
                      name:
                        on: { code: code }
                        sql:
                          file: names.sql
                        merge: [name]
                  - id: missing
                    sql:
                      file: orders.sql
                      mode: query
                    enrich:
                      name:
                        on: { code: code }
                        sql:
                          file: ghost.sql
                        merge: [name]
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // A write holds no rows, so there is nothing for a reference to fold into.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2004") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("'close'")
                        && f.message().contains("holds no rows"));
        // A reading step's references are checked like a chunk's: the file has to exist.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-BATCH-4206") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("'missing'")
                        && f.message().contains("ghost.sql"));
    }

    @Test
    void anHttpArmsModesAreTheOnesACallHas(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  http:
                    outbound:
                      allowedHosts:
                        - api.partner.example
                """);
        Files.createDirectories(dir.resolve("batch/sync"));
        Files.writeString(dir.resolve("batch/sync/writer.sql"),
                "insert into c values (/* row.code */'x')\n");
        Files.writeString(dir.resolve("batch/sync/job.yml"), """
                version: tesseraql/v1
                id: orders.sync
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: fetch
                    http:
                      url: https://api.partner.example/v1/companies
                      select: companies
                      mode: query-spool
                  - id: written
                    http:
                      url: https://api.partner.example/v1/orders
                      mode: update
                  - id: load
                    chunk:
                      reader:
                        spool: steps.fetch.spool
                      writer:
                        sql:
                          file: writer.sql
                      key: code
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // A call reads, so update is a SQL mode written on a call.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2004") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("'written'")
                        && f.message().contains("http: mode 'update'"));
        // ...and the spool a call filled is a spool: the reader's reference resolves
        // (docs/unified-sources.md decision 19a), where before only a SQL step could fill one.
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-BATCH-4206"));
    }

    @Test
    void lintsInboundWebhookRoutes(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  connectors:
                    webhooks:
                      partner:
                        secret: ${secret.env.WEBHOOK_SECRET}
                """);
        // A clean webhook route: a configured verifier and a SQL pipeline whose file exists.
        Files.createDirectories(dir.resolve("web/hooks/events"));
        Files.writeString(dir.resolve("web/hooks/events/insert.sql"), "insert into e values (1)\n");
        Files.writeString(dir.resolve("web/hooks/events/post.yml"), """
                version: tesseraql/v1
                id: events.receive
                kind: route
                recipe: webhook
                webhook:
                  provider: partner
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                """);
        // A broken webhook route: an unconfigured verifier and no SQL pipeline.
        Files.createDirectories(dir.resolve("web/hooks/bad"));
        Files.writeString(dir.resolve("web/hooks/bad/post.yml"), """
                version: tesseraql/v1
                id: bad.receive
                kind: route
                recipe: webhook
                webhook:
                  provider: ghost
                """);
        // A webhook: block on a non-webhook recipe is a misuse.
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                webhook:
                  provider: partner
                sources:
                  main:
                    sql:
                      file: get.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);

        // A webhook whose only pipeline is sources.main: it used to pass this lint and then
        // fail at startup — the compiled route always runs the transactional command
        // processor, whose pipeline is the steps: array.
        Files.createDirectories(dir.resolve("web/hooks/sourced"));
        Files.writeString(dir.resolve("web/hooks/sourced/read.sql"), "select 1\n");
        Files.writeString(dir.resolve("web/hooks/sourced/post.yml"), """
                version: tesseraql/v1
                id: sourced.receive
                kind: route
                recipe: webhook
                webhook:
                  provider: partner
                sources:
                  main:
                    sql:
                      file: read.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // Only the unconfigured verifier ('ghost') is flagged; 'partner' is configured.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-SEC-4083") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("ghost"));
        // The bad route and the sources-only route have no steps: pipeline.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1056") && f.isError())
                .hasSize(2);
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1056") && f.isError()
                        && f.source().contains("sourced"))
                .singleElement()
                .matches(f -> f.message().contains("steps: pipeline"));
        // The query-json route misuses webhook: — the same code notify:/publish: raise there.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1010") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("webhook:") && f.source().contains("items"));
    }

    @Test
    void aLocalPollSourceWithoutADeclaredRootIsAnError(@TempDir Path dir) throws Exception {
        writeLocalPollJob(dir, "path: /data/inbound", "");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4093") && f.isError());
    }

    @Test
    void aLocalPollSourceUnderADeclaredRootIsClean(@TempDir Path dir) throws Exception {
        writeLocalPollJob(dir, "path: inbound", """
                  connectors:
                    poll:
                      allowedPaths:
                        - inbound
                """);

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SEC-4093"));
    }

    @Test
    void anUnparseableDelayIsReportedRatherThanDroppingTheJobAtStartup(@TempDir Path dir)
            throws Exception {
        writeLocalPollJob(dir, "path: inbound\n    delay: every-hour", """
                  connectors:
                    poll:
                      allowedPaths:
                        - inbound
                """);

        // Without this the job throws inside wire(), is logged, and dropped — so the app boots
        // healthy and the only symptom is that nothing ever arrives.
        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1054") && f.isError()
                        && f.message().contains("every-hour"));
    }

    @Test
    void remoteOnlyKeysOnALocalSourceAreFlagged(@TempDir Path dir) throws Exception {
        writeLocalPollJob(dir,
                "path: inbound\n    host: sftp.partner.example\n    credential: partner",
                """
                          connectors:
                            poll:
                              allowedPaths:
                                - inbound
                        """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1054") && !f.isError()
                        && f.message().contains("ignores host:"));
    }

    private static void writeLocalPollJob(Path dir, String pollKeys, String connectors)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n" + connectors);
        Files.createDirectories(dir.resolve("batch/intake"));
        Files.writeString(dir.resolve("batch/intake/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/intake/job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    %s
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """.formatted(pollKeys));
    }

    @Test
    void lintsPollTriggeredFileImportJobs(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  connectors:
                    poll:
                      allowedHosts:
                        - sftp.partner.example
                      knownHostsFile: security/known_hosts
                      credentials:
                        partner-sftp:
                          username: svc
                          password: ${secret.env.SFTP_PASS}
                """);
        // A clean local poll job: known source, a path, an import block whose SQL exists.
        Files.createDirectories(dir.resolve("batch/intake"));
        Files.writeString(dir.resolve("batch/intake/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/intake/job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: /data/inbound
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        // A clean remote poll job: allow-listed host, declared credential, existing import SQL.
        Files.createDirectories(dir.resolve("batch/partner"));
        Files.writeString(dir.resolve("batch/partner/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/partner/job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: sftp
                    host: sftp.partner.example
                    path: /outbound
                    credential: partner-sftp
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        // A broken poll job: off-allow-list host, undeclared credential, and no import block.
        Files.createDirectories(dir.resolve("batch/bad"));
        Files.writeString(dir.resolve("batch/bad/job.yml"), """
                version: tesseraql/v1
                id: bad.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: sftp
                    host: evil.example
                    path: /x
                    credential: ghost
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // The clean jobs raise nothing; only evil.example is denied.
        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-SEC-4080") && f.isError())
                .singleElement()
                .matches(f -> f.message().contains("evil.example"));
        // The undeclared credential is a warning.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4081") && !f.isError()
                && f.message().contains("ghost"));
        // Both remote sources leave consumeOnce off, so both are warned about: sftp has no
        // server-side exclusion, and every replica would import every file
        // (docs/audit-hardening.md Decision 4).
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1310") && !f.isError()
                && f.message().contains("partner.intake"));
        // The poll job with no import block.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1055") && f.isError()
                && f.message().contains("bad.intake"));
        // With knownHostsFile configured, the host-key nudge stays quiet.
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-SEC-4084"));
    }

    @Test
    void refusesRowContractKeysARowCannotBeHeldTo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n"
                + "    name: contract-keys\n");
        Path route = dir.resolve("web/api/items/import");
        Files.createDirectories(route);
        Files.writeString(route.resolve("upsert.sql"), "insert into items values (1)\n");
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write
                input:
                  qty: { type: integer, min: 1, default: 7 }
                  note: { type: string, policy: hr.write }
                import:
                  format: csv
                  columns: [qty, note]
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // default: would fill a cell the file did not send; policy: authorizes a submitter
        // against one value. Neither has anything to act on when the body is rows.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1062") && f.isError()
                && f.message().contains("qty") && f.message().contains("default"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1062") && f.isError()
                && f.message().contains("note") && f.message().contains("policy"));
        // min: is a constraint and stays silent.
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1062")
                && f.message().contains("min"));
    }

    @Test
    void refusesAnImportRowContractNamingNoColumn(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n"
                + "    name: contract-lint\n");
        Path route = dir.resolve("web/api/items/import");
        Files.createDirectories(route);
        Files.writeString(route.resolve("upsert.sql"), "insert into items values (1)\n");
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: bearer
                  policy: items.write
                input:
                  qty: { type: integer, min: 1 }
                  quantity: { type: integer, min: 1 }
                import:
                  format: csv
                  columns: [name, qty]
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // `qty` is mapped and constrained; `quantity` is a rule no row would ever be held to.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1061") && f.isError()
                && f.message().contains("quantity"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-YAML-1061")
                && f.message().contains("'qty'"));
    }

    @Test
    void refusesAReviewOnAJobBecauseAJobHasNobodyToConfirmIt(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n"
                + "    name: review-lint\n");
        Files.createDirectories(dir.resolve("batch/intake"));
        Files.writeString(dir.resolve("batch/intake/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/intake/job.yml"), """
                version: tesseraql/v1
                id: orders.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: /data/inbound
                import:
                  format: csv
                  review: required
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // The shared import: block is schema-legal on both document kinds, so an editor will
        // offer the key here — which is exactly why the refusal has to be a lint, and why its
        // message says "job" rather than borrowing the route's code.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1060") && f.isError()
                && f.message().contains("orders.intake"));
    }

    @Test
    void nudgesKnownHostsFileOnUncheckedSftpPolls(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  connectors:
                    poll:
                      allowedHosts:
                        - sftp.partner.example
                      credentials:
                        partner-sftp:
                          username: svc
                          password: ${secret.env.SFTP_PASS}
                """);
        // An otherwise-clean SFTP poll job, but no knownHostsFile: the host key goes unchecked.
        Files.createDirectories(dir.resolve("batch/partner"));
        Files.writeString(dir.resolve("batch/partner/upsert.sql"), "insert into t values (1)\n");
        Files.writeString(dir.resolve("batch/partner/job.yml"), """
                version: tesseraql/v1
                id: partner.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: sftp
                    host: sftp.partner.example
                    path: /outbound
                    credential: partner-sftp
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);

        // A warning, not an error — existing apps keep shipping while being nudged.
        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4084") && !f.isError()
                        && f.message().contains("tesseraql.connectors.poll.knownHostsFile"));
    }

    @Test
    void nudgesVersionPredicateOnExpectedRowCountUpdates(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  - id: bump
                    sql:
                      file: bump.sql
                      mode: update
                      expect:
                        rowCount: 1
                """, "update orders set status = /* s */'X' where id = /* id */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2104") && !f.isError()
                        && f.message().contains("expect.rowCount"));
    }

    @Test
    void nudgesExpectOnVersionPredicateUpdates(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2105") && !f.isError()
                // The spelling the reader copies out of the message.
                        && f.message().contains("expect: { rowCount: 1 }"));
    }

    @Test
    void quietWhenUpdateDeclaresExpectAndVersionPredicate(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                      expect:
                        rowCount: 1
                """, "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2104") || f.code().equals("TQL-SQL-2105"));
    }

    /**
     * The heuristic's own blind spot, open since Phase 18: it tested the raw text for a leading
     * `update`, and every file this framework generates opens with its own checksum line. So the
     * nudge had never once reached a scaffolded statement — the shape it exists for.
     */
    @Test
    void nudgesAnUpdateStandingBehindItsScaffoldChecksumComment(@TempDir Path dir)
            throws Exception {
        writeCommandRoute(dir, """
                steps:
                  - id: bump
                    sql:
                      file: bump.sql
                      mode: update
                      expect:
                        rowCount: 1
                """, """
                -- tesseraql-scaffold-checksum: sha256:abc
                -- Scaffolded update for the orders table.
                update orders set status = /* s */'X' where id = /* id */1
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2104") && !f.isError());
    }

    /**
     * On the step that carries the directive the pairing question is already answered by the
     * compiler, so the heuristic stands down. Without this the scaffolder's own output — which
     * has a version predicate and, by construction, no expect: — would warn on every app it
     * generates.
     */
    @Test
    void quietOnTheStepCarryingADeclaredLock(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "-- tesseraql-scaffold-checksum: sha256:abc\n"
                + "-- Scaffolded update for the orders table.\n"
                + "update orders set name = /* n */'x',"
                + " version = version + 1"
                + " where id = /* id */1 and /*%lock*/ (1=1)\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().startsWith("TQL-SQL-210")
                        || f.code().equals("TQL-SQL-2116") || f.code().equals("TQL-SQL-2117"));
    }

    /**
     * A lock the statement never advances compiles, renders, matches every time and is silently
     * last-write-wins — the exact defect the declaration exists to abolish, reintroduced by a
     * declaration that looks correct. Only the lint sees the SET list.
     */
    @Test
    void warnsWhenADeclaredLockIsNeverAdvanced(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "-- tesseraql-scaffold-checksum: sha256:abc\n"
                + "-- Scaffolded update for the orders table.\n"
                + "update orders set name = /* n */'x'"
                + " where id = /* id */1 and /*%lock*/ (1=1)\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2116") && !f.isError()
                        && f.message().contains("version"));
    }

    /** A DELETE locks without advancing anything: the scaffolded delete leg must stay quiet. */
    @Test
    void quietOnALockedDeleteWhichHasNoSetList(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "delete from orders where id = /* id */1 and /*%lock*/ (1=1)\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2116"));
    }

    /**
     * A subquery in the SET list carries a WHERE of its own. Reading the first one as the
     * statement's own would cut the SET list short and report a correct statement as never
     * advancing its column — a warning on a statement the author got right.
     */
    @Test
    void quietWhenTheSetListItselfCarriesASubqueryWithAWhere(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "update orders"
                + " set name = (select n from names where id = /* id */1),"
                + " version = version + 1"
                + " where id = /* id */1 and /*%lock*/ (1=1)\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2116")
                        || f.code().equals("TQL-SQL-2117"));
    }

    /**
     * A MERGE is a legal carrier — the step's mode is constrained, never its verb — and it has no
     * WHERE clause at all: its lock belongs in an `on (…)` or a `when matched and …`. Reading a
     * missing WHERE as proof of misplacement would nag a correct statement forever.
     */
    @Test
    void quietOnALockedMergeWhichHasNoWhereClause(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "merge into orders t"
                + " using (select /* id */'ORD-1' as id from dual) s on (t.id = s.id)"
                + " when matched and /*%lock*/ (1=1)"
                + " then update set status = /* s */'X', version = version + 1\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2116")
                        || f.code().equals("TQL-SQL-2117"));
    }

    /**
     * A lock: block that forgot its column: names nothing, so 2116 has nothing to report. The
     * guess the undeclared heuristic uses must not leak into a finding as if it were declared —
     * it would send the author looking for a column their app does not have.
     */
    @Test
    void quietWhenTheLockBlockNamesNoColumn(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock:
                  type: integer
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "update orders set name = /* n */'x', rev = rev + 1"
                + " where id = /* id */1 and /*%lock*/ (1=1)\n");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-SQL-2116"));
    }

    /**
     * The nudge follows the write to every surface, but `lock:` is honoured by an HTTP command
     * route alone — offering it to a tool or a consumer would send the author into a hard
     * TQL-ROUTE-3119 at route build.
     */
    @Test
    void theLockSuggestionIsOfferedOnlyWhereALockCouldBeDeclared(@TempDir Path dir)
            throws Exception {
        String update = "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n";
        writeCommandRoute(dir, """
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, update);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/bump.sql"), update);
        Files.writeString(dir.resolve("mcp/bump.yml"), """
                version: tesseraql/v1
                id: orders.bump
                kind: tool
                recipe: command-json
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).filteredOn(f -> f.code().equals("TQL-SQL-2105")
                && f.source().contains("bump.yml"))
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.message()).contains("expect: { rowCount: 1 }")
                        .doesNotContain("lock:"));
        assertThat(findings).filteredOn(f -> f.code().equals("TQL-SQL-2105")
                && f.source().contains("post.yml"))
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.message()).contains("or declare lock: version"));
    }

    /**
     * Clause position is the one thing decision 1 leaves to a lint, because the compiler holds a
     * parse with no clause positions. The SET list here does assign the column, which is what
     * proves the two warnings are independent.
     */
    @Test
    void warnsWhenTheLockDirectiveIsNotInTheWhere(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: version
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """, "update orders set version = /*%lock*/ (1=1) where id = /* id */1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2117") && !f.isError());
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-SQL-2116"));
    }

    /**
     * The heuristic reads the declared column, not the word "version" — and it keeps running on
     * the steps of a locked command that carry no directive, which is why the suppression is per
     * carrier step rather than per route.
     */
    @Test
    void theHeuristicFollowsTheDeclaredColumnOnUncarriedSteps(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                lock: rev
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                  - id: audit
                    sql:
                      file: audit.sql
                      mode: update
                """, "update orders set name = /* n */'x', rev = rev + 1"
                + " where id = /* id */1 and /*%lock*/ (1=1)\n");
        Files.writeString(dir.resolve("web/api/orders/audit.sql"),
                "update orders_audit set note = /* n */'x'"
                        + " where id = /* id */1 and rev = /* rev */1\n");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SQL-2105") && !f.isError()
                        && f.message().contains("rev"));
    }

    /**
     * The write-safety nudge follows the write, not the mount: an MCP tool and a queue consumer
     * update rows with the same bindings a command route does, and had the check skipped
     * entirely (docs/silent-tolerance.md K-e).
     */
    @Test
    void nudgesOptimisticLockingOnToolsAndConsumersToo(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        String update = "update orders set v = v + 1 where id = /* id */1 and version = /* v */1\n";

        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/bump.sql"), update);
        Files.writeString(dir.resolve("mcp/bump.yml"), """
                version: tesseraql/v1
                id: bump
                kind: tool
                recipe: command-json
                description: Bump an order.
                security:
                  policy: orders.write
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """);

        Files.createDirectories(dir.resolve("consume/orders"));
        Files.writeString(dir.resolve("consume/orders/bump.sql"), update);
        Files.writeString(dir.resolve("consume/orders/consume.yml"), """
                version: tesseraql/v1
                id: orders.consume
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.changed
                steps:
                  - id: main
                    sql:
                      file: bump.sql
                      mode: update
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);
        // A version predicate with no expect: affects zero rows in silence — on all three now.
        assertThat(findings).filteredOn(f -> f.code().equals("TQL-SQL-2105"))
                .hasSizeGreaterThanOrEqualTo(2)
                .anyMatch(f -> f.source().contains("mcp/bump.yml"))
                .anyMatch(f -> f.source().contains("consume/orders/consume.yml"));
    }

    @Test
    void reportsMissingStepSqlFile(@TempDir Path dir) throws Exception {
        writeCommandRoute(dir, """
                steps:
                  - id: header
                    sql:
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
                steps:
                  - id: main
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
                steps:
                  - id: main
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
                sources:
                  main:
                    sql:
                      file: search.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(dir.resolve("web/api/items/search.sql"),
                "select * from t where a = /* limit */1\n");

        // query-json is routed into the transactional command pipeline as soon as it declares a
        // validate: block, so the rules do run — rejecting them here blocked a working route at
        // error severity.
        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().equals("TQL-YAML-1003"));
    }

    @Test
    void reportsValidateOnARecipeThatCannotRunIt(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: page
                validate:
                  positive:
                    rule: query.limit > 0
                    field: limit
                sources:
                  main:
                    sql:
                      file: search.sql
                response:
                  html:
                    template: items.html
                """);
        Files.writeString(dir.resolve("web/items/search.sql"),
                "select * from t where a = /* limit */1\n");

        // A page never reaches the transactional command pipeline, so its rules are dropped.
        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-YAML-1003") && f.isError()
                        && f.message().contains("page"));
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
                steps:
                  - id: main
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
                      ok: steps.main.affectedRows
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
                sources:
                  main:
                    contract:
                      name: identity.list-users
                response:
                  json:
                    body:
                      data: main.rows
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
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      data: main.rows
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
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        tenant_id: tenant.id
                response:
                  json:
                    body:
                      data: main.rows
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
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      data: main.rows
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
                export:
                  format: pdf
                  sheet: data
                  template: print.xlsx
                sources:
                  main:
                    sql:
                      file: print.sql
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
                export:
                  format: pdf
                  template: print.html
                sources:
                  main:
                    sql:
                      file: print.sql
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
                      audience: https://app.example.com
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
                      audience: https://app.example.com
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
                      audience: https://app.example.com
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
                      audience: https://app.example.com
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4043") && f.isError());
    }

    @Test
    void acceptsValidRs256JwksConfig(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                      jwksUri: https://idp.example.com/jwks
                      audience:
                        - https://app.example.com
                """)).noneMatch(f -> f.code().startsWith("TQL-SEC-404") && f.isError());
    }

    /**
     * A JWT configuration that names no audience is refused, not warned about
     * (docs/audit-hardening.md Decision 1).
     *
     * <p>This is the case that made the campaign's largest blast radius worth paying: with an
     * external jwksUri and no declared audience, a token that IdP minted for another relying party
     * authenticates here. A warning would have left that open by default.
     */
    @Test
    void flagsJwtConfigWithoutAnAudience(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      algorithm: RS256
                      jwksUri: https://idp.example.com/jwks
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4048") && f.isError());
    }

    /**
     * The exclusion warning fires for every transport that has not claimed its files
     * (docs/audit-hardening.md Decision 4, docs/camel-removal.md slice 6c).
     *
     * <p>It used to skip local sources, and the reason was borrowed: the library's changed
     * strategy extended its marker-file one and wrote an atomic lock file, so a local source did
     * have inter-process exclusion without asking for it. The connectors are the framework's own
     * now and none of them writes a marker, so a local source is in exactly the position the
     * warning describes — and this test asserted the silence rather than the property, which is
     * why nothing failed when the property went away. It now asserts both halves: a source that
     * claims is quiet, and one that does not is warned about whatever its transport.
     */
    @Test
    void theExclusionWarningSkipsDeclaredSourcesAndCatchesLocalOnes(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("batch/local"));
        Files.writeString(dir.resolve("batch/local/job.yml"), """
                version: tesseraql/v1
                id: local.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: local
                    path: inbox
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        Files.createDirectories(dir.resolve("batch/declared"));
        Files.writeString(dir.resolve("batch/declared/job.yml"), """
                version: tesseraql/v1
                id: declared.intake
                kind: job
                recipe: file-import
                trigger:
                  poll:
                    transport: sftp
                    host: sftp.partner.example
                    path: /outbound
                    credential: partner-sftp
                    consumeOnce: true
                import:
                  format: csv
                pipeline:
                  - id: row
                    sql:
                      file: upsert.sql
                """);

        assertThat(new AppLinter().lint(dir))
                .as("the sftp source claims its files, so it is quiet")
                .noneMatch(f -> f.code().equals("TQL-YAML-1310")
                        && f.message().contains("declared.intake"))
                .as("the local source claims nothing, and nothing else excludes it any more")
                .anyMatch(f -> f.code().equals("TQL-YAML-1310")
                        && f.message().contains("local.intake"));
    }

    /**
     * A liveness window no wider than the heartbeat that fills it is refused
     * (docs/audit-hardening.md Decision 6).
     */
    @Test
    void flagsALivenessWindowShorterThanTheHeartbeatInterval(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  batch:
                    heartbeat:
                      interval: 60s
                      livenessWindow: 30s
                """)).anyMatch(f -> f.code().equals("TQL-BATCH-4211") && f.isError());
    }

    /** Equal is refused too: it leaves no room for the jitter a real pulse carries. */
    @Test
    void flagsALivenessWindowEqualToTheHeartbeatInterval(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  batch:
                    heartbeat:
                      interval: 30s
                      livenessWindow: 30s
                """)).anyMatch(f -> f.code().equals("TQL-BATCH-4211") && f.isError());
    }

    /** The shipped defaults are a valid pair, which is worth pinning rather than assuming. */
    @Test
    void theDefaultHeartbeatAndWindowAreAcceptedTogether(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  batch:
                    heartbeat:
                      interval: 30s
                      livenessWindow: 5m
                """)).noneMatch(f -> f.code().equals("TQL-BATCH-4211"));
        // And with nothing declared at all, which is how most apps run.
        assertThat(lintWithConfig(dir, "")).noneMatch(f -> f.code().equals("TQL-BATCH-4211"));
    }

    /**
     * A read primitive nothing gates is reported; anything at all silences it
     * (docs/audit-hardening.md open question 4).
     */
    @Test
    void flagsAnMcpReadPrimitiveWithNoFloorAtAll(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("mcp/list.yml"), """
                version: tesseraql/v1
                id: ungoverned.read
                kind: tool
                recipe: query-json
                description: Reads rows.
                sources:
                  main:
                    sql:
                      file: list.sql
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-MCP-4261") && !f.isError()
                        && f.message().contains("ungoverned.read"));
    }

    /** The defaults block is resolved into the document, so declaring it silences the warning. */
    @Test
    void theMcpDefaultsBlockSuppliesTheFloor(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/application.yml"), "server:\n  port: 0\n");
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    defaults:
                      mcp:
                        auth: bearer
                        policy: mcp.read
                    policies:
                      mcp.read:
                        anyOf:
                          - role: AGENT
                """);
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("mcp/list.yml"), """
                version: tesseraql/v1
                id: floored.read
                kind: tool
                recipe: query-json
                description: Reads rows.
                sources:
                  main:
                    sql:
                      file: list.sql
                """);

        assertThat(new AppLinter().lint(dir)).noneMatch(f -> f.code().equals("TQL-MCP-4261"));
    }

    /** A single string is accepted where a list would be; the model holds a list either way. */
    @Test
    void acceptsASingleAudienceString(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      secret: s
                      audience: https://app.example.com
                """)).noneMatch(f -> f.code().equals("TQL-SEC-4048"));
    }

    /**
     * A jwt block that cannot validate anything is not asked for an audience.
     *
     * <p>The rule keys off the same condition the runtime uses to bind a verifier at all — a secret
     * or a key source — so a block carrying only claim mappings is left alone rather than being
     * told to declare an audience for tokens it will never see.
     */
    @Test
    void doesNotAskForAnAudienceWhenNoKeyMaterialIsConfigured(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    jwt:
                      rolesClaim: roles
                """)).noneMatch(f -> f.code().equals("TQL-SEC-4048"));
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

    /**
     * An enabled SAML SP with no {@code sp.acsUrl} silently stops checking the assertion's
     * SubjectConfirmation recipient. The URL stays optional (IdP-initiated-only deployments have
     * none), so this is a warning — the {@code TQL-SEC-4065} stance for the analogous optional
     * mTLS trustBundle, whose asymmetry with SAML's silence is what this closes.
     */
    @Test
    void warnsSamlWithoutAcsUrl(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  saml:
                    enabled: true
                    sp:
                      audience: https://app.example.com/saml
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4092") && !f.isError()
                && f.message().contains("recipient"));
    }

    @Test
    void acceptsSamlWithAcsUrlAndIsSilentWhenDisabled(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  saml:
                    enabled: true
                    sp:
                      audience: https://app.example.com/saml
                      acsUrl: https://app.example.com/_tesseraql/saml/acs
                """)).noneMatch(f -> f.code().equals("TQL-SEC-4092"));

        // A SAML block that is present but off is not a deployment choice to warn about.
        assertThat(lintWithConfig(dir, """
                  saml:
                    enabled: false
                    sp:
                      audience: https://app.example.com/saml
                """)).noneMatch(f -> f.code().equals("TQL-SEC-4092"));
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
                  auth: api-key
                sources:
                  main:
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

    /**
     * The untyped {@code san:} is gone, not aliased: it matched a value against every kind of
     * Subject Alternative Name, so a certificate carrying it as an email or URI satisfied a matcher
     * that meant DNS. A config still using it must fail, not quietly mean something weaker.
     */
    @Test
    void rejectsTheRemovedUntypedMtlsSanMatcher(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        billing:
                          san: "spiffe://acme/ns/default/sa/billing"
                          roles: [SERVICE]
                """);
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4066") && f.isError()
                && f.message().contains("'billing'"));
        // It is not a matcher any more, so the client also has none at all.
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4062") && f.isError());
    }

    @Test
    void acceptsTypedMtlsSanMatchersAndFlagsTwoOfThem(@TempDir Path dir) throws Exception {
        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        billing:
                          sanUri: "spiffe://acme/ns/default/sa/billing"
                          roles: [SERVICE]
                """)).noneMatch(f -> f.code().startsWith("TQL-SEC-406") && f.isError());

        assertThat(lintWithConfig(dir, """
                  security:
                    mtls:
                      forwardedHeader: ssl-client-cert
                      trustBundle: ca-pem
                      clients:
                        billing:
                          sanUri: "spiffe://acme/ns/default/sa/billing"
                          sanDns: api.internal
                          roles: [SERVICE]
                """)).anyMatch(f -> f.code().equals("TQL-SEC-4063") && f.isError());
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
                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                """);

        assertThat(new AppLinter().lint(dir))
                .anyMatch(f -> f.code().equals("TQL-SEC-4060") && f.isError());
    }
}
