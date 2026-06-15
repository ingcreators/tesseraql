package io.tesseraql.operations.workflow;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.SqlScripts;
import io.tesseraql.core.workflow.WorkflowStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * JDBC-backed {@link WorkflowStore} (roadmap Phase 28, slice 1): the managed
 * {@code tql_workflow_instance} state row and the append-only {@code tql_workflow_history} trail.
 *
 * <p>Every write takes the caller's {@link Connection} so the state advance and history append
 * commit atomically with the transition's business writes. The instance id is derived from
 * {@code doc_type:doc_id}, so {@link #ensureInstance} is naturally idempotent and a document maps to
 * exactly one instance.
 */
public final class JdbcWorkflowStore implements WorkflowStore {

    /** TQL-WORKFLOW-3220: the workflow store could not complete a JDBC operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3220);

    private final DataSource dataSource;

    public JdbcWorkflowStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates {@code tql_workflow_instance} and {@code tql_workflow_history} (per dialect). */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcWorkflowStore.class,
                    "/tesseraql/db/migration/workflow/V1__workflow.sql");
        } catch (SQLException ex) {
            throw error("Failed to create workflow schema", ex);
        }
    }

    @Override
    public String currentState(Connection cx, String docType, String docId) {
        try (PreparedStatement ps = cx.prepareStatement("select current_state from "
                + "tql_workflow_instance where doc_type = ? and doc_id = ?")) {
            ps.setString(1, docType);
            ps.setString(2, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException ex) {
            throw error("Failed to read workflow state", ex);
        }
    }

    @Override
    public void ensureInstance(Connection cx, String docType, String docId, String initial,
            String tenantId) {
        if (currentState(cx, docType, docId) != null) {
            return;
        }
        try (PreparedStatement ps = cx.prepareStatement("insert into tql_workflow_instance "
                + "(instance_id, doc_type, doc_id, current_state, tenant_id, updated_at) "
                + "values (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, instanceId(docType, docId));
            ps.setString(2, docType);
            ps.setString(3, docId);
            ps.setString(4, initial);
            ps.setString(5, tenantId);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to create workflow instance", ex);
        }
    }

    @Override
    public int advanceState(Connection cx, String docType, String docId, String from, String to) {
        try (PreparedStatement ps = cx.prepareStatement("update tql_workflow_instance "
                + "set current_state = ?, updated_at = ? "
                + "where doc_type = ? and doc_id = ? and current_state = ?")) {
            ps.setString(1, to);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, docType);
            ps.setString(4, docId);
            ps.setString(5, from);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to advance workflow state", ex);
        }
    }

    @Override
    public void appendHistory(Connection cx, History entry) {
        try (PreparedStatement ps = cx.prepareStatement("insert into tql_workflow_history "
                + "(history_id, instance_id, doc_type, doc_id, transition, from_state, to_state, "
                + "actor, acted_at, note) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, instanceId(entry.docType(), entry.docId()));
            ps.setString(3, entry.docType());
            ps.setString(4, entry.docId());
            ps.setString(5, entry.transition());
            ps.setString(6, entry.fromState());
            ps.setString(7, entry.toState());
            ps.setString(8, entry.actor());
            ps.setTimestamp(9, Timestamp.from(entry.at() == null ? Instant.now() : entry.at()));
            ps.setString(10, entry.note());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to append workflow history", ex);
        }
    }

    private static String instanceId(String docType, String docId) {
        return docType + ":" + docId;
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
