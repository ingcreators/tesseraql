package io.tesseraql.maven;

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
}
