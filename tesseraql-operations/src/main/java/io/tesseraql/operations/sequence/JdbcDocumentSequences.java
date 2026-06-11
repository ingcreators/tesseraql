package io.tesseraql.operations.sequence;

import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sequence.DocumentSequences;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.util.SqlScripts;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * The managed document-number sequence contract (roadmap Phase 18), backed by
 * {@code tql_doc_sequence}.
 *
 * <p>Gapless with row-lock semantics: the allocating {@code UPDATE} takes the sequence row's
 * lock on the caller's connection, so concurrent allocations serialize until the surrounding
 * command transaction commits, and a rollback returns the number. The statements are bundled,
 * SQL-tool-runnable 2-way SQL files next to this class.
 */
public final class JdbcDocumentSequences implements DocumentSequences {

    private static final TqlErrorCode ALLOCATION_ERROR = new TqlErrorCode(TqlDomain.SQL, 2610);
    /** Two attempts suffice: a lost seed race is retried once against the committed row. */
    private static final int MAX_ATTEMPTS = 3;

    private final List<SqlNode> increment = parse("increment.sql");
    private final List<SqlNode> current = parse("current.sql");
    private final List<SqlNode> seed = parse("seed.sql");
    private final DataSource dataSource;

    public JdbcDocumentSequences(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Creates the sequence table if absent, from the bundled V2 migration script. */
    public void ensureSchema() {
        try {
            SqlScripts.applyForVendor(dataSource, JdbcDocumentSequences.class,
                    "/tesseraql/db/migration/operations/V2__document_sequences.sql");
        } catch (SQLException ex) {
            throw error("Failed to create document-sequence schema", ex);
        }
    }

    @Override
    public long next(Connection connection, String name) {
        try {
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                Long allocated = incrementExisting(connection, name);
                if (allocated != null) {
                    return allocated;
                }
                // First use: seed the sequence, allocating 1. The seed runs under a savepoint so
                // a lost race (unique violation) does not abort the surrounding transaction;
                // the loser retries the UPDATE against the winner's committed row.
                Savepoint savepoint = connection.setSavepoint();
                try {
                    execute(connection, seed, name);
                    return 1L;
                } catch (SQLException ex) {
                    connection.rollback(savepoint);
                    if (!SqlErrors.isUniqueViolation(ex)) {
                        throw ex;
                    }
                } finally {
                    connection.releaseSavepoint(savepoint);
                }
            }
            throw error("Sequence '" + name + "' could not be allocated after "
                    + MAX_ATTEMPTS + " attempts", null);
        } catch (SQLException ex) {
            throw error("Sequence '" + name + "' allocation failed", ex);
        }
    }

    /** Increments an existing sequence row and reads the allocated value, or null when absent. */
    private Long incrementExisting(Connection connection, String name) throws SQLException {
        if (execute(connection, increment, name) == 0) {
            return null;
        }
        BoundSql bound = SqlRenderer.render(current, Map.of("name", name));
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw error("Sequence '" + name + "' row vanished mid-allocation", null);
                }
                return resultSet.getLong(1);
            }
        }
    }

    private int execute(Connection connection, List<SqlNode> nodes, String name)
            throws SQLException {
        BoundSql bound = SqlRenderer.render(nodes, Map.of("name", name));
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            return statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        for (int i = 0; i < bound.parameters().size(); i++) {
            statement.setObject(i + 1, bound.parameters().get(i).value());
        }
    }

    private static List<SqlNode> parse(String resource) {
        return Sql2WayParser.parse(SqlScripts.read(JdbcDocumentSequences.class, resource));
    }

    private static TqlException error(String message, Exception cause) {
        return TqlException.builder(ALLOCATION_ERROR).message(message).cause(cause).build();
    }
}
