package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import io.tesseraql.security.Principal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Delegates a workflow task to another principal (roadmap Phase 28 slice 3): the caller — who must
 * hold the document's open task — reassigns it to the delegate, who then sees it in their inbox. The
 * delegate is the {@code {to}} path segment; only the current assignee (or a candidate-group member)
 * may delegate, else {@code TQL-WORKFLOW-3203} (403).
 */
public final class WorkflowDelegateProcessor implements Processor {

    /** TQL-WORKFLOW-3203: the caller holds no actionable task for the document (HTTP 403). */
    private static final TqlErrorCode NOT_ASSIGNED = new TqlErrorCode(TqlDomain.WORKFLOW, 3203);
    /** TQL-WORKFLOW-3210: delegation needs the runtime's WorkflowTaskStore bean. */
    private static final TqlErrorCode NO_TASK_STORE = new TqlErrorCode(TqlDomain.WORKFLOW, 3210);
    /** TQL-WORKFLOW-3222: the delegation transaction failed. */
    private static final TqlErrorCode TX_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3222);

    private final String workflowId;
    private final String docType;
    private final String datasourceName;

    public WorkflowDelegateProcessor(String workflowId, String docType, String datasourceName) {
        this.workflowId = workflowId;
        this.docType = docType;
        this.datasourceName = datasourceName;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        Map<String, Object> path = context == null
                ? Map.of()
                : (Map<String, Object>) context.getOrDefault("path", Map.of());
        String docId = path.get("key") == null ? null : String.valueOf(path.get("key"));
        String to = path.get("to") == null ? null : String.valueOf(path.get("to"));
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL, Principal.class);
        String subject = principal == null ? null : principal.subject();
        List<String> groups = principal == null ? List.of() : principal.groups();

        WorkflowTaskStore taskStore = exchange.getContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.WORKFLOW_TASK_STORE_BEAN, WorkflowTaskStore.class);
        if (taskStore == null) {
            throw new TqlException(NO_TASK_STORE,
                    "Workflow '" + workflowId + "' delegation needs a task store");
        }
        DataSource dataSource = exchange.getContext().getRegistry()
                .lookupByNameAndType(datasourceName, DataSource.class);
        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                if (!taskStore.canAct(connection, docType, docId, subject, groups)) {
                    throw new TqlException(NOT_ASSIGNED, "Workflow '" + workflowId
                            + "': only the assignee may delegate this task");
                }
                // Handing a task to someone absent forwards it once (roadmap Phase 52) -
                // the same one-hop rule as assignment; the task records who it was meant for.
                io.tesseraql.core.workflow.Delegations.Resolved resolved = io.tesseraql.core.workflow.Delegations
                        .resolve(exchange.getContext().getRegistry().lookupByNameAndType(
                                TesseraqlProperties.DELEGATION_STORE_BEAN,
                                io.tesseraql.core.workflow.DelegationStore.class),
                                tenantOf(exchange), to);
                taskStore.reassignOpenTasks(connection, docType, docId, resolved.assignee(),
                        resolved.delegatedFrom());
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex instanceof TqlException tql
                        ? tql
                        : new TqlException(TX_ERROR, "Delegation failed: " + ex.getMessage(), ex);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
        exchange.getMessage().setBody(Map.of("ok", true));
    }

    private static String tenantOf(org.apache.camel.Exchange exchange) {
        Principal principal = exchange.getProperty(TesseraqlProperties.PRINCIPAL,
                Principal.class);
        return principal == null ? null : principal.tenantId();
    }
}
