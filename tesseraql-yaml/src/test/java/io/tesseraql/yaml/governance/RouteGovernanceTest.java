package io.tesseraql.yaml.governance;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.governance.RouteGovernance.Assessment;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteGovernanceTest {

    @TempDir
    Path dir;

    private AppManifest app(Map<String, String> routeFiles, String tesseraqlYml) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                tesseraqlYml == null ? "tesseraql:\n  app:\n    name: t\n" : tesseraqlYml);
        for (Map.Entry<String, String> entry : routeFiles.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue());
        }
        return new ManifestLoader().load(dir);
    }

    private static final String MANAGED_READ = """
            version: tesseraql/v1
            id: users.search
            kind: route
            recipe: query-json
            input:
              q:
                type: string
            security:
              auth: bearer
              policy: users.read
            sql:
              file: search.sql
              params:
                q: query.q
            response:
              json:
                body:
                  data: sql.rows
            """;

    private static final String UNAUTHENTICATED_WRITE = """
            version: tesseraql/v1
            id: users.deactivate
            kind: route
            recipe: command-json
            sql:
              file: deactivate.sql
              mode: update
              params:
                id: body.id
            response:
              json:
                body:
                  affected: sql.affectedRows
            """;

    private static final String SERVICE_PAGE = """
            version: tesseraql/v1
            id: ops.page
            kind: route
            recipe: query-html
            security:
              auth: bearer
              policy: ops.batch.view
            sql:
              service: ops.overview
            response:
              html:
                template: page.html
            """;

    @Test
    void derivesModesAndRiskFactors() throws Exception {
        AppManifest manifest = app(Map.of(
                "web/api/users/get.yml", MANAGED_READ,
                "web/api/users/deactivate/post.yml", UNAUTHENTICATED_WRITE,
                "web/ops/get.yml", SERVICE_PAGE), null);

        List<Assessment> assessments = RouteGovernance.assess(manifest);

        Assessment managed = byId(assessments, "users.search");
        assertThat(managed.mode()).isEqualTo("managed");
        assertThat(managed.riskScore()).isZero();

        Assessment advanced = byId(assessments, "users.deactivate");
        assertThat(advanced.mode()).isEqualTo("advanced");
        assertThat(advanced.riskFactors())
                .anySatisfy(f -> assertThat(f).contains("without authentication"))
                .anySatisfy(f -> assertThat(f).contains("idempotency"))
                .anySatisfy(f -> assertThat(f).contains("undeclared request input(s): id"));
        assertThat(advanced.riskScore()).isGreaterThanOrEqualTo(6);

        Assessment extended = byId(assessments, "ops.page");
        assertThat(extended.mode()).isEqualTo("extended");
        assertThat(extended.riskFactors())
                .anySatisfy(f -> assertThat(f).contains("service provider"));
        assertThat(extended.sha256()).hasSize(64);
    }

    private static final String AUTHENTICATED_RESOURCE = """
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
            """;

    private static final String PUBLIC_RESOURCE = """
            version: tesseraql/v1
            id: public-catalog
            kind: resource
            recipe: query-json
            uri: tesseraql://public
            description: Public catalog.
            security:
              auth: public
            sql:
              file: catalog.sql
            """;

    @Test
    void assessesMcpResourcesAsReadOnlyNeverAdvanced() throws Exception {
        AppManifest manifest = app(Map.of(
                "mcp/catalog.yml", AUTHENTICATED_RESOURCE,
                "mcp/public.yml", PUBLIC_RESOURCE,
                "mcp/catalog.sql", "select 1\n"), null);

        List<Assessment> assessments = RouteGovernance.assess(manifest);

        Assessment authenticated = byId(assessments, "catalog");
        assertThat(authenticated.mode()).isEqualTo("managed");
        assertThat(authenticated.riskScore()).isZero();
        assertThat(authenticated.sha256()).hasSize(64);

        Assessment open = byId(assessments, "public-catalog");
        assertThat(open.mode()).isEqualTo("managed");
        assertThat(open.riskFactors())
                .anySatisfy(f -> assertThat(f).contains("unauthenticated MCP resource"));
        assertThat(open.riskScore()).isEqualTo(1);
    }

    @Test
    void gateRequiresApprovalForConfiguredModesAndScores() throws Exception {
        String policy = """
                tesseraql:
                  app:
                    name: t
                  governance:
                    maxRiskScore: 3
                    requireApproval: [advanced]
                """;
        AppManifest manifest = app(Map.of(
                "web/api/users/get.yml", MANAGED_READ,
                "web/api/users/deactivate/post.yml", UNAUTHENTICATED_WRITE), policy);

        GovernanceGate.Report report = new GovernanceGate(manifest).check(manifest);

        // The managed read passes; the unauthenticated write needs review and has no approval.
        assertThat(report.violations()).hasSize(1);
        GovernanceGate.Violation violation = report.violations().get(0);
        assertThat(violation.routeId()).isEqualTo("users.deactivate");
        assertThat(violation.reason()).contains("requires approval").contains("no approval");
    }

    @Test
    void approvalPinsTheReviewedHashAndEditsInvalidateIt() throws Exception {
        String policy = """
                tesseraql:
                  app:
                    name: t
                  governance:
                    requireApproval: [advanced]
                """;
        AppManifest manifest = app(Map.of(
                "web/api/users/deactivate/post.yml", UNAUTHENTICATED_WRITE), policy);
        String sha = RouteGovernance.assess(manifest).get(0).sha256();

        // Approving the current hash clears the gate.
        Files.createDirectories(dir.resolve("governance"));
        Files.writeString(dir.resolve("governance/approvals.yml"), """
                approvals:
                  - route: users.deactivate
                    sha256: %s
                    approvedBy: reviewer
                """.formatted(sha));
        assertThat(new GovernanceGate(manifest).check(manifest).violations()).isEmpty();

        // Editing the approved route invalidates the approval.
        Files.writeString(dir.resolve("web/api/users/deactivate/post.yml"),
                UNAUTHENTICATED_WRITE + "# edited\n");
        AppManifest edited = new ManifestLoader().load(dir);
        GovernanceGate.Report report = new GovernanceGate(edited).check(edited);
        assertThat(report.violations()).hasSize(1);
        assertThat(report.violations().get(0).reason()).contains("edited since its approval");
    }

    private static Assessment byId(List<Assessment> assessments, String id) {
        return assessments.stream().filter(a -> a.routeId().equals(id)).findFirst().orElseThrow();
    }
}
