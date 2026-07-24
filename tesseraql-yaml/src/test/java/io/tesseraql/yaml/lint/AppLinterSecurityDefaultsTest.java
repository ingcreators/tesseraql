package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints around the path-matched route security defaults (docs/route-defaults.md). */
class AppLinterSecurityDefaultsTest {

    @Test
    void flagsTheRetiredKindKeyedDefaultsShape(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    defaults:
                      htmx:
                        auth: browser
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4130") && !f.isError());
    }

    @Test
    void flagsAPublicRouteUnderAPolicyDefaultedPath(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    defaults:
                      routes:
                        - match: /admin/**
                          auth: browser
                          policy: admin.view
                    policies:
                      admin.view:
                        anyOf:
                          - role: ADMIN
                """);
        Files.createDirectories(dir.resolve("web/admin/ping"));
        Files.writeString(dir.resolve("web/admin/ping/get.yml"), """
                version: tesseraql/v1
                id: admin.ping
                kind: route
                recipe: query-json
                security:
                  auth: public
                sql:
                  file: ping.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/admin/ping/ping.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4131") && !f.isError()
                && f.source().equals("web/admin/ping/get.yml"));
    }

    @Test
    void aCoveredNonPublicRouteLintsClean(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    defaults:
                      routes:
                        - match: /admin/**
                          auth: browser
                          policy: admin.view
                    policies:
                      admin.view:
                        anyOf:
                          - role: ADMIN
                """);
        Files.createDirectories(dir.resolve("web/admin/audit"));
        Files.writeString(dir.resolve("web/admin/audit/get.yml"), """
                version: tesseraql/v1
                id: admin.audit
                kind: route
                recipe: query-json
                sql:
                  file: audit.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/admin/audit/audit.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .noneMatch(f -> f.code().equals("TQL-SEC-4130")
                        || f.code().equals("TQL-SEC-4131"))
                .noneMatch(LintFinding::isError);
    }
}
