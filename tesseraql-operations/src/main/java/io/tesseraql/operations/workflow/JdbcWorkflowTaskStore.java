package io.tesseraql.operations.workflow;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.core.workflow.WorkflowTaskStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link WorkflowTaskStore} (roadmap Phase 28, slice 2): the managed
 * {@code tql_workflow_task} inbox. Every write takes the caller's {@link Connection} so task
 * creation and completion commit atomically with the transition.
 */
public final class JdbcWorkflowTaskStore implements WorkflowTaskStore {

    /** TQL-WORKFLOW-3222: the workflow task store could not complete a JDBC operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3222);

    private final DataSource dataSource;

    public JdbcWorkflowTaskStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_workflow_task} (per dialect) if it does not exist. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcWorkflowTaskStore.class,
                    "/tesseraql/db/migration/workflow-task/V1__workflow_task.sql");
        } catch (SQLException ex) {
            throw error("Failed to create workflow task schema", ex);
        }
    }

    @Override
    public void openTask(Connection cx, Task task) {
        try (PreparedStatement ps = cx.prepareStatement("insert into tql_workflow_task "
                + "(task_id, doc_type, doc_id, state, assignee, candidate_group, status, "
                + "created_at, due_at, tenant_id) values (?, ?, ?, ?, ?, ?, 'OPEN', ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, task.docType());
            ps.setString(3, task.docId());
            ps.setString(4, task.state());
            ps.setString(5, task.assignee());
            ps.setString(6, task.candidateGroup());
            ps.setTimestamp(7, Timestamp.from(Instant.now()));
            ps.setTimestamp(8, task.dueAt() == null ? null : Timestamp.from(task.dueAt()));
            ps.setString(9, task.tenantId());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to open workflow task", ex);
        }
    }

    @Override
    public void reassignOpenTasks(Connection cx, String docType, String docId, String newAssignee) {
        try (PreparedStatement ps = cx.prepareStatement("update tql_workflow_task "
                + "set assignee = ? where doc_type = ? and doc_id = ? and status = 'OPEN'")) {
            ps.setString(1, newAssignee);
            ps.setString(2, docType);
            ps.setString(3, docId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to reassign workflow tasks", ex);
        }
    }

    @Override
    public void escalate(Connection cx, String taskId, String newAssignee) {
        // Clearing due_at makes the cluster-safe sweeper act on a breached task exactly once.
        try (PreparedStatement ps = cx.prepareStatement("update tql_workflow_task "
                + "set assignee = ?, due_at = null where task_id = ? and status = 'OPEN'")) {
            ps.setString(1, newAssignee);
            ps.setString(2, taskId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to escalate workflow task", ex);
        }
    }

    @Override
    public List<Overdue> overdue(Connection cx, Instant asOf, int limit) {
        List<Overdue> overdue = new ArrayList<>();
        try (PreparedStatement ps = cx.prepareStatement("select task_id, doc_type, doc_id, state, "
                + "assignee from tql_workflow_task "
                + "where status = 'OPEN' and due_at is not null and due_at < ? order by due_at")) {
            ps.setTimestamp(1, Timestamp.from(asOf));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && overdue.size() < limit) {
                    overdue.add(new Overdue(rs.getString("task_id"), rs.getString("doc_type"),
                            rs.getString("doc_id"), rs.getString("state"),
                            rs.getString("assignee")));
                }
            }
        } catch (SQLException ex) {
            throw error("Failed to read overdue workflow tasks", ex);
        }
        return overdue;
    }

    @Override
    public void completeOpenTasks(Connection cx, String docType, String docId, String actor) {
        try (PreparedStatement ps = cx.prepareStatement("update tql_workflow_task "
                + "set status = 'DONE', completed_at = ?, completed_by = ? "
                + "where doc_type = ? and doc_id = ? and status = 'OPEN'")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, actor);
            ps.setString(3, docType);
            ps.setString(4, docId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to complete workflow tasks", ex);
        }
    }

    @Override
    public boolean hasOpenTasks(Connection cx, String docType, String docId) {
        try (PreparedStatement ps = cx.prepareStatement("select 1 from tql_workflow_task "
                + "where doc_type = ? and doc_id = ? and status = 'OPEN'")) {
            ps.setString(1, docType);
            ps.setString(2, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw error("Failed to read workflow tasks", ex);
        }
    }

    @Override
    public boolean canAct(Connection cx, String docType, String docId, String subject,
            Collection<String> groups) {
        List<String> groupList = groups == null ? List.of() : new ArrayList<>(groups);
        StringBuilder sql = new StringBuilder("select 1 from tql_workflow_task "
                + "where doc_type = ? and doc_id = ? and status = 'OPEN' and (assignee = ?");
        if (!groupList.isEmpty()) {
            sql.append(" or candidate_group in (")
                    .append(String.join(", ", java.util.Collections.nCopies(groupList.size(), "?")))
                    .append(')');
        }
        sql.append(')');
        try (PreparedStatement ps = cx.prepareStatement(sql.toString())) {
            ps.setString(1, docType);
            ps.setString(2, docId);
            ps.setString(3, subject);
            for (int i = 0; i < groupList.size(); i++) {
                ps.setString(4 + i, groupList.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw error("Failed to check workflow task authority", ex);
        }
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
