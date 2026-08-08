package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.TransitionSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A workflow document consumes shared decisions exactly as a route does
 * (docs/decision-tables.md "Acting on the result"): each transition's {@code decide:}
 * references resolve at manifest load, with the same errors.
 */
class WorkflowDecideResolutionTest {

    private Path app(@TempDir Path dir, String decide) throws Exception {
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
                      route: { type: string, enum: [manager, director] }
                    rows:
                      - when: { amount: "> 100000" }
                        outputs: { route: director }
                      - outputs: { route: manager }
                """);
        Files.createDirectories(dir.resolve("workflow"));
        Files.writeString(dir.resolve("workflow/purchase.yml"), """
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
                  - { id: done, type: terminal }
                transitions:
                  - id: submit
                    from: draft
                    to: manager_review
                    guard: "decision.approvalRoute.route == 'manager'"
                %s
                  - id: approve
                    from: manager_review
                    to: done
                """.formatted(decide));
        return dir;
    }

    @Test
    void aTransitionReferenceResolvesTheSharedDecision(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                    decide:
                      approvalRoute:
                        use: approvalRoute
                        params: { amount: params.amount }
                """);

        AppManifest manifest = new ManifestLoader().load(app);
        TransitionSpec submit = manifest.workflows().get(0).definition().transitions().get(0);

        assertThat(submit.decide().get("approvalRoute").decision()).isNotNull();
        assertThat(submit.decide().get("approvalRoute").decision().rows()).hasSize(2);
    }

    @Test
    void anUnknownReferenceOnATransitionFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                    decide:
                      approvalRoute:
                        use: nope
                        params: { amount: params.amount }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4705")
                .hasMessageContaining("nope");
    }
}
