package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A bearer caller with nothing to verify its token (TQL-SEC-4047). The JWT lints ran only when a
 * {@code jwt:} block was present, so the app that declared none — the one where nothing can
 * authenticate at all — was the one nothing checked. The operations API is mounted in every
 * application and reads with {@code auth: bearer}, so that app ships an ops API no caller can
 * use, discoverable only by calling it.
 */
class AppLinterBearerConfigTest {

    private Path app(Path dir, String securityConfig, String routeAuth) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n" + securityConfig);
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                method: GET
                path: /api/items
                security:
                  auth: %s
                sql:
                  file: list.sql
                response:
                  json:
                    body:
                      rows: sql.rows
                """.formatted(routeAuth));
        return dir;
    }

    private static final String JWT = """
              security:
                jwt:
                  secret: dev-only-secret-change-me-in-production
            """;

    @Test
    void aBearerRouteWithNoJwtConfigIsReported(@TempDir Path dir) throws Exception {
        // A warning, not an error like its api-key and mTLS siblings: a JWT block is
        // verification material — usually a secret — so it is the one auth configuration that
        // legitimately lives in a config/env/ overlay the lint never sees.
        List<LintFinding> findings = new AppLinter().lint(app(dir, "", "bearer"));
        assertThat(findings)
                .filteredOn(f -> "TQL-SEC-4047".equals(f.code()))
                .extracting(LintFinding::severity)
                .containsExactly("warning");
    }

    @Test
    void anAppThatDeclaresNoBearerRouteIsNotNagged(@TempDir Path dir) throws Exception {
        // The operations API is framework-mounted, not authored. Reporting it would fire on
        // every application that has not configured bearer auth, and a finding that appears
        // everywhere is one everybody learns to scroll past. The operator who calls that API
        // gets TQL-SEC-4001, which names the unbound authenticator.
        List<LintFinding> findings = new AppLinter().lint(app(dir, "", "public"));
        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-SEC-4047");
    }

    @Test
    void aConfiguredAppIsQuiet(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, JWT, "bearer"));
        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-SEC-4047");
    }

    @Test
    void anMcpToolDeclaringApiKeyIsCheckedLikeARoute(@TempDir Path dir) throws Exception {
        // TQL-SEC-4044 read manifest.routes() only, so a consumer or a tool declaring
        // auth: api-key was never checked — the same route-shaped-surface gap twice over.
        Path app = app(dir, JWT, "bearer");
        Files.createDirectories(app.resolve("mcp/tools/pick"));
        Files.writeString(app.resolve("mcp/tools/pick/pick.sql"), "select id from items\n");
        Files.writeString(app.resolve("mcp/tools/pick/tool.yml"), """
                version: tesseraql/v1
                id: items.pick
                kind: tool
                recipe: query-json
                description: Picks an item.
                security:
                  auth: api-key
                sql:
                  file: pick.sql
                response:
                  json:
                    body:
                      rows: sql.rows
                """);
        List<LintFinding> findings = new AppLinter().lint(app);
        assertThat(findings)
                .filteredOn(f -> "TQL-SEC-4044".equals(f.code()))
                .extracting(LintFinding::message)
                .anyMatch(message -> message.contains("items.pick"));
    }
}
