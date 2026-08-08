package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for approval workflows (roadmap Phase 28, {@code TQL-WORKFLOW-31xx}). */
class AppLinterWorkflowTest {

    /** Writes a well-formed managed workflow with its command files; tests mutate it per case. */
    private static void writeWorkflow(Path dir, String body) throws Exception {
        Files.createDirectories(dir.resolve("workflow"));
        Files.writeString(dir.resolve("workflow/submit.sql"),
                "update purchase_requests set last_action = 'submit' where id = /* key */ 'x'\n");
        Files.writeString(dir.resolve("workflow/approve.sql"),
                "update purchase_requests set last_action = 'approve' where id = /* key */ 'x'\n");
        Files.writeString(dir.resolve("workflow/purchase_request.yml"), body);
    }

    private static final String WELL_FORMED = """
            version: tesseraql/v1
            id: purchase_request
            kind: workflow
            mode: managed
            document:
              type: purchase_request
              table: purchase_requests
              key: id
            initial: draft
            states:
              - { id: draft, type: initial }
              - { id: submitted }
              - { id: approved, type: terminal }
              - { id: rejected, type: terminal }
            transitions:
              - { id: submit, from: draft, to: submitted, guard: "document.amount > 0", command: submit.sql }
              - { id: approve, from: submitted, to: approved, command: approve.sql }
              - { id: reject, from: submitted, to: rejected, command: approve.sql }
            """;

    private static List<String> codes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-WORKFLOW"))
                .toList();
    }

    @Test
    void escalateTransitionFromWrongStateIsAnError(@TempDir Path dir) throws Exception {
        // submit starts from draft, not the deadline's 'submitted' state, so it can never advance.
        writeWorkflow(dir, WELL_FORMED + "deadlines:\n"
                + "  - { state: submitted, within: 1h, onBreach: { escalate: submit } }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3107");
    }

    @Test
    void wellFormedWorkflowProducesNoWorkflowFindings(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void undeclaredStateInTransitionIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("to: submitted, guard", "to: ghost, guard"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3101");
    }

    @Test
    void unreachableStateIsAnError(@TempDir Path dir) throws Exception {
        // Drop the submit transition, so 'submitted' (and its successors) are unreachable from draft.
        writeWorkflow(dir, WELL_FORMED.replace(
                "  - { id: submit, from: draft, to: submitted, guard: \"document.amount > 0\","
                        + " command: submit.sql }\n",
                ""));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3102");
    }

    @Test
    void guardOutsideAllowedRootsIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("document.amount > 0", "order.amount > 0"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3103");
    }

    @Test
    void missingCommandFileIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("command: submit.sql", "command: nope.sql"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3104");
    }

    @Test
    void aSqlGuardFileLintsCleanAndAWriteFileIsRefused(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("guard: \"document.amount > 0\"",
                "guard: { file: lines-priced.sql, code: unpriced }"));
        Files.writeString(dir.resolve("workflow/lines-priced.sql"),
                "-- rows mean pass\nselect 1 from lines where doc = /* key */ 'x'\n");
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();

        Files.writeString(dir.resolve("workflow/lines-priced.sql"),
                "update lines set price = 1\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3109");
    }

    @Test
    void stampLintsColumnIdentifiersAndDecisionAliases(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("command: submit.sql }",
                "command: submit.sql, stamp: { \"bad-col\": 1 } }"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3111");

        writeWorkflow(dir, WELL_FORMED.replace("command: submit.sql }",
                "command: submit.sql, stamp: { lane: decision.ghost.route } }"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3111");

        writeWorkflow(dir, WELL_FORMED.replace("command: submit.sql }",
                "command: submit.sql, stamp: { lane: body.route } }"));
        assertThat(new AppLinter().lint(dir)).anyMatch(f -> f.code()
                .equals("TQL-WORKFLOW-3111") && !f.isError());
    }

    @Test
    void aWellFormedDispatchLintsClean(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED
                .replace("id: approve, from: submitted, to: approved,",
                        "id: approve, from: submitted, to: approved,"
                                + " guard: \"document.amount > 0\",")
                + "dispatch:\n  - { id: decide, oneOf: [approve, reject] }\n");
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aDispatchWithOneMemberOrAnUnknownMemberIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED + "dispatch:\n  - { id: decide, oneOf: [approve] }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");

        writeWorkflow(dir, WELL_FORMED
                + "dispatch:\n  - { id: decide, oneOf: [approve, ghost] }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");
    }

    @Test
    void dispatchMembersStartingFromDifferentStatesIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir,
                WELL_FORMED + "dispatch:\n  - { id: decide, oneOf: [submit, approve] }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");
    }

    @Test
    void aDispatchIdCollidingWithATransitionIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir,
                WELL_FORMED + "dispatch:\n  - { id: approve, oneOf: [approve, reject] }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");
    }

    @Test
    void dispatchMembersWithDifferentSecuritySpecsIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED
                .replace("id: approve, from: submitted, to: approved,",
                        "id: approve, from: submitted, to: approved,"
                                + " security: { auth: bearer, policy: other.act },")
                + "dispatch:\n  - { id: decide, oneOf: [approve, reject] }\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");
    }

    /** The shared decision the dispatch-decide tests wire (docs/transition-engine.md). */
    private static void writeRoutingDecision(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("decisions"));
        Files.writeString(dir.resolve("decisions/routing.yml"), """
                version: tesseraql/v1
                decisions:
                  routing:
                    inputs:
                      amount: { type: number, match: between }
                    outputs:
                      lane: { type: string, enum: [fast, slow] }
                    rows:
                      - when: { amount: ">= 1000" }
                        outputs: { lane: slow }
                      - outputs: { lane: fast }
                """);
    }

    @Test
    void aMemberGuardMayReadTheDispatchLevelDecision(@TempDir Path dir) throws Exception {
        writeRoutingDecision(dir);
        writeWorkflow(dir, WELL_FORMED
                .replace("id: approve, from: submitted, to: approved,",
                        "id: approve, from: submitted, to: approved,"
                                + " guard: \"decision.routing.lane == 'fast'\",")
                .replace("id: reject, from: submitted, to: rejected,",
                        "id: reject, from: submitted, to: rejected,"
                                + " guard: \"decision.routing.lane == 'slow'\",")
                + """
                        dispatch:
                          - id: decide_next
                            decide:
                              routing: { use: routing, params: { amount: document.amount } }
                            oneOf: [approve, reject]
                        """);
        // No 4711 (the members inherit the dispatch's alias), no 4712 (both enum values
        // covered), no workflow findings.
        assertThat(new AppLinter().lint(dir)).isEmpty();
    }

    @Test
    void aDispatchDecideAliasCollidingWithAMemberIsAnError(@TempDir Path dir) throws Exception {
        writeRoutingDecision(dir);
        writeWorkflow(dir, WELL_FORMED
                .replace("id: approve, from: submitted, to: approved,",
                        "id: approve, from: submitted, to: approved, decide:"
                                + " { routing: { use: routing, params:"
                                + " { amount: document.amount } } },")
                + """
                        dispatch:
                          - id: decide_next
                            decide:
                              routing: { use: routing, params: { amount: document.amount } }
                            oneOf: [approve, reject]
                        """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3112");
    }

    @Test
    void anUnguardedMemberThatIsNotLastIsAWarning(@TempDir Path dir) throws Exception {
        // 'approve' has no guard, so 'reject' after it can never be attempted.
        writeWorkflow(dir,
                WELL_FORMED + "dispatch:\n  - { id: decide, oneOf: [approve, reject] }\n");
        assertThat(new AppLinter().lint(dir)).anyMatch(f -> f.code()
                .equals("TQL-WORKFLOW-3113") && !f.isError());
    }

    @Test
    void aDocTypeLiteralNamingNoDeclaredDocumentTypeIsAWarning(@TempDir Path dir)
            throws Exception {
        writeWorkflow(dir, WELL_FORMED);
        Files.createDirectories(dir.resolve("rules"));
        // The typo ('purchase_requests', the table, not the declared type) that today
        // survives to runtime as an always-empty join.
        Files.writeString(dir.resolve("rules/approved.sql"), """
                select 1 from tql_workflow_instance wi
                where wi.doc_type = 'purchase_requests' and wi.doc_id = /* id */ 'x'
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(f -> f.code()
                .equals("TQL-WORKFLOW-3114") && !f.isError());

        // The declared type lints clean.
        Files.writeString(dir.resolve("rules/approved.sql"), """
                select 1 from tql_workflow_instance wi
                where wi.doc_type = 'purchase_request' and wi.doc_id = /* id */ 'x'
                """);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aCurrentStateLiteralNamingNoDeclaredStateIsAWarning(@TempDir Path dir)
            throws Exception {
        writeWorkflow(dir, WELL_FORMED);
        Files.createDirectories(dir.resolve("rules"));
        // 'approved ' with a trailing typo character survives to runtime as an
        // always-empty join today; a declared state lints clean.
        Files.writeString(dir.resolve("rules/approved.sql"), """
                select 1 from tql_workflow_instance wi
                where wi.doc_type = 'purchase_request'
                  and wi.current_state = 'aproved' and wi.doc_id = /* id */ 'x'
                """);
        assertThat(new AppLinter().lint(dir)).anyMatch(f -> f.code()
                .equals("TQL-WORKFLOW-3115") && !f.isError());

        Files.writeString(dir.resolve("rules/approved.sql"), """
                select 1 from tql_workflow_instance wi
                where wi.doc_type = 'purchase_request'
                  and wi.current_state = 'approved' and wi.doc_id = /* id */ 'x'
                """);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aPinnedDocTypeNarrowsTheStateSpace(@TempDir Path dir) throws Exception {
        // Two workflows: 'other' declares state 'shipped', purchase_request does not.
        writeWorkflow(dir, WELL_FORMED);
        Files.writeString(dir.resolve("workflow/other.yml"), """
                version: tesseraql/v1
                id: other
                kind: workflow
                mode: managed
                document: { type: other, table: purchase_requests, key: id }
                initial: open
                states:
                  - { id: open, type: initial }
                  - { id: shipped, type: terminal }
                transitions:
                  - { id: ship, from: open, to: shipped, command: approve.sql }
                """);
        Files.createDirectories(dir.resolve("rules"));
        // The file pins purchase_request, so 'shipped' — a real state, of the WRONG
        // workflow — warns; without the pin the union would have hidden the mismatch.
        Files.writeString(dir.resolve("rules/narrowed.sql"), """
                select 1 from tql_workflow_instance wi
                where wi.doc_type = 'purchase_request'
                  and wi.current_state = 'shipped' and wi.doc_id = /* id */ 'x'
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3115");
    }

    @Test
    void aDocTypeColumnOfTheAppsOwnTableIsOutOfScope(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED);
        // No tql_workflow_instance reference: the app's own doc_type column, any value.
        Files.createDirectories(dir.resolve("rules"));
        Files.writeString(dir.resolve("rules/own-column.sql"), """
                select 1 from attachments where doc_type = 'invoice'
                """);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aMissingGuardFileIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("guard: \"document.amount > 0\"",
                "guard: { file: nope.sql }"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3104");
    }

    @Test
    void aGuardWithBothFormsOrNeitherIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("guard: \"document.amount > 0\"",
                "guard: { expression: \"document.amount > 0\", file: lines-priced.sql }"));
        Files.writeString(dir.resolve("workflow/lines-priced.sql"), "select 1\n");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3108");

        writeWorkflow(dir, WELL_FORMED.replace("guard: \"document.amount > 0\"",
                "guard: { code: no-forms }"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3108");
    }

    @Test
    void managedModeWithoutDocumentTypeIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("  type: purchase_request\n", ""));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3106");
    }

    @Test
    void appModeWithoutStateColumnIsAnError(@TempDir Path dir) throws Exception {
        writeWorkflow(dir, WELL_FORMED.replace("mode: managed", "mode: app"));
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-WORKFLOW-3106");
    }

    @Test
    void invalidWorkflowModeConfigIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  workflow:
                    mode: bogus
                """);
        assertThat(new AppLinter().lint(dir).stream().map(LintFinding::code).toList())
                .contains("TQL-WORKFLOW-3110");
    }
}
