package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around decision tables (docs/decision-tables.md): a {@code decision.*} bind with no
 * {@code decide:} entry can only fail at runtime, a first-hit row a previous row fully covers
 * never fires, and a decision nothing references is dead or a missed reference.
 */
class AppLinterDecisionsTest {

    private Path app(@TempDir Path dir, String decide, String sql) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("decisions"));
        Files.writeString(dir.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string }
                    rows:
                      - when: { amount: ">= 10000" }
                        out: { route: manager }
                      - out: { route: auto }
                """);
        Files.createDirectories(dir.resolve("web/requests/new"));
        Files.writeString(dir.resolve("web/requests/new/post.yml"), """
                version: tesseraql/v1
                id: requests.create
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  policy: req.write
                input:
                  total: { type: integer, required: true }
                %ssteps:
                  create:
                    file: create.sql
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(decide));
        Files.writeString(dir.resolve("web/requests/new/create.sql"), sql);
        return dir;
    }

    private static final String DECIDE = """
            decide:
              approvalRoute:
                use: approvalRoute
                params: { amount: params.total }
            """;

    @Test
    void aDecisionBindWithoutADecideEntryIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "insert into requests (route) values (/* decision.approvalRoute.route */'x')\n"));

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4711")
                && finding.isError()
                && finding.message().contains("decision.approvalRoute.route"));
    }

    @Test
    void aWiredDecisionBindLintsCleanAndCountsAsReferenced(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, DECIDE,
                "insert into requests (route) values (/* decision.approvalRoute.route */'x')\n"));

        assertThat(findings).noneMatch(finding -> finding.code().startsWith("TQL-DECISION-"));
    }

    @Test
    void anUnreferencedDecisionIsAWarning(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "", "select 1\n"));

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4716")
                && !finding.isError()
                && finding.message().contains("approvalRoute"));
    }

    private static final String TABLE_BACKED = """
            version: tesseraql/v1

            decisions:
              approvalRoute:
                inputs:
                  dept: { type: string, match: orgSubtree }
                  amount: { type: integer, match: between }
                outputs:
                  route: { type: string }
                source:
                  table: approval_route_rules
                  match:
                    dept: { subtree: dept_unit }
                    amount: { between: [amount_min, amount_max] }
                  priority: priority
                  outputs: { route: route }
            """;

    private static final String DECIDE_TABLE_BACKED = """
            decide:
              approvalRoute:
                use: approvalRoute
                params: { dept: principal.subject, amount: params.total }
            """;

    @Test
    void orgSubtreeWithoutManagedOrgUnitsIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, DECIDE_TABLE_BACKED,
                "insert into requests (route) values (/* decision.approvalRoute.route */'x')\n");
        Files.writeString(app.resolve("decisions/approval.yml"), TABLE_BACKED);

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4717")
                && finding.isError());

        Files.writeString(app.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  orgunit:
                    mode: managed
                """);
        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().equals("TQL-DECISION-4717"));
    }

    /**
     * The rows are runtime data, but the shape of their table is checkable at build — when
     * the introspection sidecar is present; a fresh checkout without one degrades silently.
     */
    @Test
    void theSidecarChecksTheMappingAgainstTheRealDdl(@TempDir Path dir) throws Exception {
        Path app = app(dir, DECIDE_TABLE_BACKED,
                "insert into requests (route) values (/* decision.approvalRoute.route */'x')\n");
        Files.writeString(app.resolve("decisions/approval.yml"), TABLE_BACKED);
        Files.writeString(app.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n  orgunit:\n    mode: managed\n");
        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().equals("TQL-DECISION-4710"));

        Files.createDirectories(app.resolve(".tesseraql/docs"));
        Files.writeString(app.resolve(".tesseraql/docs/schema.json"), """
                { "schemaVersion": 1, "datasources": { "main": { "tables": [
                  { "name": "approval_route_rules", "type": "TABLE", "columns": [
                    { "name": "id" }, { "name": "dept_unit" }, { "name": "amount_min" },
                    { "name": "priority" }, { "name": "route" }
                  ], "primaryKey": [], "foreignKeys": [], "uniqueIndexes": [] }
                ] } } }
                """);

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4710")
                && finding.isError()
                && finding.message().contains("amount_max"));
    }

    @Test
    void aStepWhenReferencingAnUndeclaredDecisionIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, "", "select 1\n");
        Files.writeString(app.resolve("web/requests/new/post.yml"), """
                version: tesseraql/v1
                id: requests.create
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                  policy: req.write
                input:
                  total: { type: integer, required: true }
                steps:
                  create:
                    file: create.sql
                    when: "decision.approvalRoute.route == 'manager'"
                response:
                  json:
                    body:
                      ok: "true"
                """);

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4711")
                && finding.isError()
                && finding.message().contains("step 'create' when:"));
    }

    private Path workflowApp(@TempDir Path dir, String enumValues, String transitions)
            throws Exception {
        Path app = app(dir, "", "select 1\n");
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string, enum: [%s] }
                    rows:
                      - when: { amount: "> 100000" }
                        out: { route: director }
                      - out: { route: manager }
                """.formatted(enumValues));
        Files.createDirectories(app.resolve("workflow"));
        Files.writeString(app.resolve("workflow/purchase.yml"), """
                version: tesseraql/v1
                id: purchase
                kind: workflow
                document:
                  type: purchase
                  table: purchases
                  key: id
                  stateColumn: wf_state
                initial: draft
                states:
                  - { id: draft, type: initial }
                  - { id: manager_review }
                  - { id: director_review }
                  - { id: done, type: terminal }
                transitions:
                %s
                  - id: finish
                    from: manager_review
                    to: done
                  - id: finish2
                    from: director_review
                    to: done
                """.formatted(transitions));
        return app;
    }

    private static final String GUARDED_SUBMITS = """
              - id: submit-manager
                from: draft
                to: manager_review
                guard: "decision.approvalRoute.route == 'manager'"
                decide:
                  approvalRoute:
                    use: approvalRoute
                    params: { amount: params.amount }
              - id: submit-director
                from: draft
                to: director_review
                guard: "decision.approvalRoute.route == 'director'"
                decide:
                  approvalRoute:
                    use: approvalRoute
                    params: { amount: params.amount }
            """;

    /** manager and director are handled; the enum also allows cfo — the unhandled else. */
    @Test
    void anUnhandledEnumValueAcrossAStateIsAWarning(@TempDir Path dir) throws Exception {
        Path app = workflowApp(dir, "manager, director, cfo", GUARDED_SUBMITS);

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4712")
                && !finding.isError()
                && finding.message().contains("cfo"));
    }

    @Test
    void aFullyCoveredStateLintsClean(@TempDir Path dir) throws Exception {
        Path app = workflowApp(dir, "manager, director", GUARDED_SUBMITS);

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().equals("TQL-DECISION-4712"));
    }

    @Test
    void comparingToAValueTheDecisionCannotProduceIsAnError(@TempDir Path dir)
            throws Exception {
        Path app = workflowApp(dir, "manager, director", GUARDED_SUBMITS.replace(
                "decision.approvalRoute.route == 'director'",
                "decision.approvalRoute.route == 'directer'"));

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4713")
                && finding.isError()
                && finding.message().contains("directer"));
        // The guard root itself is legal now: no workflow guard-root finding for decision.*.
        assertThat(findings)
                .noneMatch(finding -> finding.code().equals("TQL-WORKFLOW-3103"));
    }

    @Test
    void aRowShadowedByAnEarlierRowIsAWarning(@TempDir Path dir) throws Exception {
        Path app = app(dir, DECIDE,
                "insert into requests (route) values (/* decision.approvalRoute.route */'x')\n");
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string }
                    rows:
                      - when: { amount: ">= 100" }
                        out: { route: manager }
                      - when: { amount: "500..900" }
                        out: { route: director }
                      - out: { route: auto }
                """);

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(finding -> finding.code().equals("TQL-DECISION-4715")
                && !finding.isError()
                && finding.message().contains("row 2"));
    }
}
