package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints around the ambient {@code principal.*} binds (docs/ambient-params.md). */
class AppLinterAmbientPrincipalTest {

    private Path app(@TempDir Path dir, String security, String sql, String params)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                %s
                sql:
                  file: search.sql
                  mode: query
                %s
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(security, params));
        Files.writeString(dir.resolve("web/api/items/search.sql"), sql);
        return dir;
    }

    @Test
    void aPrincipalBindOnAPublicRouteIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "security:\n  auth: public",
                "select 1 where tenant_id = /* principal.tenantId */'t'\n", ""));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4136") && f.isError()
                && f.message().contains("principal.tenantId"));
    }

    @Test
    void aRouteWithoutAnyEffectiveSecurityIsFlaggedToo(@TempDir Path dir) throws Exception {
        // No security block and no app defaults: nothing ever seeds a principal here.
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "select 1 where owner = /* principal.loginId */'x'\n", ""));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4136"));
    }

    @Test
    void aRenameWiringDrawsTheMigrationNudge(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "security:\n  auth: bearer",
                "select 1 where owner = /* actor */'x'\n",
                "  params:\n    actor: principal.loginId"));

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4137") && !f.isError()
                && f.message().contains("/* principal.loginId */"));
    }

    @Test
    void serviceInvocationParamsNeverDrawTheNudge(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/prefs"));
        Files.writeString(dir.resolve("web/api/prefs/post.yml"), """
                version: tesseraql/v1
                id: prefs.save
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                sql:
                  service: account.app.save
                  params:
                    subject: principal.subject
                response:
                  json:
                    body:
                      ok: "true"
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        // Service params are the service's arguments, not SQL binds.
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-SEC-4137"));
    }

    @Test
    void anAuthenticatedAmbientBindLintsClean(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "security:\n  auth: bearer",
                "select 1 where roles in /* principal.roles */('A') and owner ="
                        + " /* principal.loginId */'x'\n",
                ""));

        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-SEC-413"));
    }
}
