package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.DecisionUse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest loader resolves shared decision-table references (docs/decision-tables.md): the
 * decision carries the contract and the rows, the reference carries the wiring — and every
 * declared decision compiles at load, so a malformed table fails the build, not the first
 * request.
 */
class DecisionResolutionTest {

    private Path app(@TempDir Path dir, String decide) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("decisions"));
        Files.writeString(dir.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      category: { type: string, match: in }
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string, enum: [manager, director, auto] }
                    hitPolicy: first
                    rows:
                      - when: { category: [office-supplies, books], amount: ">= 10000" }
                        outputs: { route: manager }
                      - when: { amount: "> 100000" }
                        outputs: { route: director }
                      - outputs: { route: auto }
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
                  category: { type: string, required: true }
                  total: { type: integer, required: true }
                decide:
                %s
                steps:
                  create:
                    sql:
                      file: create.sql
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(decide));
        Files.writeString(dir.resolve("web/requests/new/create.sql"),
                "insert into requests (category, route) values"
                        + " (/* params.category */'a', /* decision.approvalRoute.route */'x')\n");
        return dir;
    }

    @Test
    void aReferenceResolvesTheSharedDecisionUnderTheLocalWiring(@TempDir Path dir)
            throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");

        AppManifest manifest = new ManifestLoader().load(app);
        DecisionUse resolved = manifest.routes().get(0).definition().decide()
                .get("approvalRoute");

        assertThat(resolved.use()).isEqualTo("approvalRoute");
        assertThat(resolved.decision()).isNotNull();
        assertThat(resolved.decision().rows()).hasSize(3);
    }

    @Test
    void anUnknownReferenceFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: shippingFee\n"
                + "    params: { category: params.category, amount: params.total }\n");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4705")
                .hasMessageContaining("shippingFee");
    }

    @Test
    void theWiringIsCheckedAgainstTheInputsExactly(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category }\n");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4706")
                .hasMessageContaining("amount");
    }

    /**
     * Decisions evaluate before the document loads and before any step runs, so a wiring
     * expression reading {@code document.*} or {@code steps.*} would resolve null and match
     * only wildcards — a quiet wrong answer the load rejects loudly instead.
     */
    @Test
    void aWiringExpressionReadingRuntimeStateFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: document.category, amount: params.total }\n");

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4707")
                .hasMessageContaining("document");
    }

    @Test
    void aMalformedDeclaredDecisionFailsTheLoadEvenUnreferenced(@TempDir Path dir)
            throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");
        Files.writeString(app.resolve("decisions/broken.yml"), """
                version: tesseraql/v1

                decisions:
                  feeTier:
                    inputs:
                      weight: { type: integer, match: between }
                    outputs:
                      fee: { type: integer }
                    hitPolicy: unique
                    rows:
                      - when: { weight: "5..15" }
                        outputs: { fee: 1 }
                      - when: { weight: ">= 10" }
                        outputs: { fee: 2 }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4714");
    }

    /** The typo in a routing row dies at build, not in the branch nobody tested. */
    @Test
    void aRowOutputOutsideTheDeclaredEnumFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      category: { type: string, match: in }
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string, enum: [manager, director, auto] }
                    rows:
                      - when: { amount: "> 100000" }
                        outputs: { route: directer }
                      - outputs: { route: auto }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4708")
                .hasMessageContaining("directer");
    }

    /** An output typed through a field domain inherits the domain's enum as its value space. */
    @Test
    void aDomainTypedOutputInheritsTheDomainValueSpace(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");
        Files.createDirectories(app.resolve("domains"));
        Files.writeString(app.resolve("domains/approval.yml"), """
                version: tesseraql/v1

                domains:
                  approvalLane:
                    type: string
                    enum: [manager, director, auto]
                """);
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      category: { type: string, match: in }
                      amount: { type: integer, match: between }
                    outputs:
                      route: { domain: approvalLane }
                    rows:
                      - when: { amount: "> 100000" }
                        outputs: { route: cfo }
                      - outputs: { route: auto }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4708")
                .hasMessageContaining("cfo");
    }

    @Test
    void aCellLiteralOutsideTheInputTypeFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");
        Files.writeString(app.resolve("decisions/approval.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      category: { type: integer, match: in }
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string }
                    rows:
                      - when: { category: [office-supplies], amount: "> 100000" }
                        outputs: { route: director }
                      - outputs: { route: auto }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4708")
                .hasMessageContaining("office-supplies");
    }

    @Test
    void aDuplicateDecisionNameFailsTheLoad(@TempDir Path dir) throws Exception {
        Path app = app(dir, "  approvalRoute:\n"
                + "    use: approvalRoute\n"
                + "    params: { category: params.category, amount: params.total }\n");
        Files.writeString(app.resolve("decisions/second.yml"), """
                version: tesseraql/v1

                decisions:
                  approvalRoute:
                    inputs:
                      amount: { type: integer, match: between }
                    outputs:
                      route: { type: string }
                    rows:
                      - outputs: { route: auto }
                """);

        assertThatThrownBy(() -> new ManifestLoader().load(app))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-DECISION-4701");
    }
}
