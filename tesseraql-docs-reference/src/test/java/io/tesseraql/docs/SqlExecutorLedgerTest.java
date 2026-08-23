package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The JDBC-boilerplate ledger (docs/contract-sql-execution.md slice 7, "Guards"): every main
 * source file that calls {@code prepareStatement} is named here. The campaign measured ~856
 * lines of structurally duplicated prepare/bind/bound/execute/read sequences that copy-paste
 * detection cannot see, and retired the executors it could; this ledger keeps them retired.
 *
 * <p><b>A new entry is refused by default.</b> Rendered SQL meets JDBC in
 * {@code io.tesseraql.core.sql.SqlStatement}; a new hand-rolled executor is the defect class
 * this campaign closed (unbounded, unclassified, unobserved statements), so a build that adds
 * one must say so here, in review, with a reason. Removing an entry — an executor adopting the
 * primitive, a store retired — just shrinks the list.
 *
 * <p>The framework's fixed-SQL stores ({@code Jdbc*Store} and friends) are listed and legitimate:
 * their SQL is compiled into the jar and reviewed with it. The remaining declared-SQL executors
 * are recorded in the design document with the reason each keeps its own statement code (a
 * streaming or capped read, a positional bind plan, compile-time construction).
 */
class SqlExecutorLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-core/src/main/java/io/tesseraql/core/sql/SqlStatement.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/MysqlPlanInspector.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/OraclePlanInspector.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/PostgresPlanInspector.java",
            "tesseraql-oauth/src/main/java/io/tesseraql/oauth/JdbcOAuthStore.java",
            "tesseraql-oauth/src/main/java/io/tesseraql/oauth/SigningKeys.java",
            "tesseraql-oidc/src/main/java/io/tesseraql/oidc/OidcStateStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/account/JdbcPreferenceStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/account/JdbcShortcutStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/attachment/JdbcAttachmentStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/audit/JdbcRouteAuditStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/ChunkStepRunner.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/JobRepository.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/SqlStepRunner.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/catalog/JdbcCatalogStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/credential/JdbcCredentialTokenStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/credential/JdbcTotpStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/files/JdbcFileTransferService.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/idempotency/JdbcIdempotencyStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/inbox/JdbcInboxStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/messaging/JdbcEventChannelStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/org/JdbcOrgUnitStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/outbox/JdbcOutboxStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/poll/JdbcPollConsumedStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/rate/JdbcRateLeaseStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/retention/RetentionSweeper.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/sequence/JdbcDocumentSequences.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/spool/JdbcTempStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/webhook/JdbcWebhookReplayStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/workflow/JdbcDelegationStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/workflow/JdbcWorkflowStore.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/workflow/JdbcWorkflowTaskStore.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/CrossNodeTopicBus.java",
            "tesseraql-saml/src/main/java/io/tesseraql/saml/routes/SamlReplayGuard.java",
            "tesseraql-scim/src/main/java/io/tesseraql/scim/JdbcScimResourceMapping.java",
            "tesseraql-security/src/main/java/io/tesseraql/security/session/JdbcSessionStore.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/StudioDataService.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/StudioTestService.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/SuiteContext.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/WorkflowCases.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/calendar/Calendars.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/enrich/KeyedReference.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/workflow/ColumnWorkflowStore.java"));

    @Test
    void everyPrepareStatementSiteIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .forEach(path -> {
                        try {
                            if (Files.readString(path).contains("prepareStatement(")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files calling prepareStatement — a NEW entry means a new"
                        + " hand-rolled executor: route it through io.tesseraql.core.sql"
                        + ".SqlStatement instead, or add it here in review with a reason;"
                        + " a REMOVED entry just shrinks this list")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
