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
