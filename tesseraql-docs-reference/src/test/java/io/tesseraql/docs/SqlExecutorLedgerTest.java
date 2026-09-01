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
 *
 * <p>The scan covers every JDBC statement construction — {@code prepareStatement},
 * {@code createStatement}, {@code prepareCall} — not just prepares: the one executor of
 * user-declared SQL that bypassed the primitive after the campaign (the tenant registry) did it
 * on a bare {@code createStatement}, which the original grep never saw. The
 * {@code createStatement} entries are fixed framework SQL of shapes a prepare cannot carry or
 * does not fit: DDL and migration scripts ({@code SqlScripts}, {@code IdentityBootstrap}),
 * {@code LISTEN}/{@code NOTIFY} loops ({@code PgNotifyListener}, {@code TopicNotifyBridge}),
 * plan inspection ({@code SqlServerPlanInspector}), and the CLI's local conveniences.
 */
class SqlExecutorLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-apptasks/src/main/java/io/tesseraql/apptasks/IdentityBootstrap.java",
            "tesseraql-cli/src/main/java/io/tesseraql/cli/DuckDbCommand.java",
            "tesseraql-cli/src/main/java/io/tesseraql/cli/FirstAdminHint.java",
            "tesseraql-core/src/main/java/io/tesseraql/core/sql/SqlStatement.java",
            "tesseraql-core/src/main/java/io/tesseraql/core/util/SqlScripts.java",
            "tesseraql-coverage-core/src/main/java/io/tesseraql/coverage/plan/SqlServerPlanInspector.java",
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
            "tesseraql-operations/src/main/java/io/tesseraql/operations/batch/JobRepository.java",
            // The bulk-report handle store (docs/bulk-report.md decision 6): a keyed
            // framework store like its Jdbc siblings, not a route executor.
            "tesseraql-operations/src/main/java/io/tesseraql/operations/bulk/JdbcBulkReportStore.java",
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
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/PgNotifyListener.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/TopicNotifyBridge.java",
            "tesseraql-saml/src/main/java/io/tesseraql/saml/routes/SamlReplayGuard.java",
            "tesseraql-scim/src/main/java/io/tesseraql/scim/JdbcScimResourceMapping.java",
            "tesseraql-security/src/main/java/io/tesseraql/security/session/JdbcSessionStore.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/StudioDataService.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/StudioTestService.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/SuiteContext.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/WorkflowCases.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/calendar/Calendars.java",
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/workflow/ColumnWorkflowStore.java"));

    @Test
    void everyStatementConstructionSiteIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // Hidden directories are never main sources, and a .claude/ worktree on
                    // disk carries stale copies of exactly the files this ledger greps.
                    .filter(path -> !path.toString().contains("/."))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (source.contains("prepareStatement(")
                                    || source.contains("createStatement(")
                                    || source.contains("prepareCall(")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files constructing a JDBC statement (prepareStatement,"
                        + " createStatement, prepareCall) — a NEW entry means a new hand-rolled"
                        + " executor: route it through io.tesseraql.core.sql.SqlStatement"
                        + " instead, or add it here in review with a reason; a REMOVED entry"
                        + " just shrinks this list")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
