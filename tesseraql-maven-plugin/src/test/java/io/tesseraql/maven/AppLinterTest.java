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
