package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.workflow.WorkflowStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * The app-mode {@link WorkflowStore} (roadmap Phase 28 slice 1): a document's state lives in a
 * column on its own business table, so nothing managed is provisioned. Constructed per workflow with
 * the table, key column, and state column — all author-supplied build-time identifiers, never
 * request input, so the column SQL composes them directly (the same trust model as a scope's
 * {@code on <alias>}).
 *
 * <p>{@link #ensureInstance} and {@link #appendHistory} are no-ops: the app owns the row, and an app
 * that wants an audit trail writes it through its own command SQL.
 */
public final class ColumnWorkflowStore implements WorkflowStore {

    /** TQL-WORKFLOW-3221: the app-mode workflow column store could not complete a JDBC operation. */
    private static final TqlErrorCode STORE_ERROR = new TqlErrorCode(TqlDomain.WORKFLOW, 3221);

    private final String table;
    private final String keyColumn;
    private final String stateColumn;

    public ColumnWorkflowStore(String table, String keyColumn, String stateColumn) {
        this.table = table;
        this.keyColumn = keyColumn;
        this.stateColumn = stateColumn;
    }

    @Override
    public String currentState(Connection cx, String docType, String docId) {
        try (PreparedStatement ps = cx.prepareStatement(
                "select " + stateColumn + " from " + table + " where " + keyColumn + " = ?")) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException ex) {
            throw error("Failed to read workflow state column", ex);
        }
    }

    @Override
    public void ensureInstance(Connection cx, String docType, String docId, String initial,
            String tenantId) {
        // App mode keeps state on the business row the app already owns; nothing to provision.
    }

    @Override
    public int advanceState(Connection cx, String docType, String docId, String from, String to) {
        try (PreparedStatement ps = cx.prepareStatement("update " + table + " set " + stateColumn
                + " = ? where " + keyColumn + " = ? and " + stateColumn + " = ?")) {
            ps.setString(1, to);
            ps.setString(2, docId);
            ps.setString(3, from);
            return ps.executeUpdate();
        } catch (SQLException ex) {
            throw error("Failed to advance workflow state column", ex);
        }
    }

    @Override
    public void appendHistory(Connection cx, History entry) {
        // App mode does not own a managed history table; history is an optional app contract.
    }

    private static TqlException error(String message, SQLException ex) {
        return TqlException.builder(STORE_ERROR).message(message + ": " + ex.getMessage()).cause(ex)
                .build();
    }
}
