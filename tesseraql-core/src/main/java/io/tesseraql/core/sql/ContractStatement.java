package io.tesseraql.core.sql;

import io.tesseraql.core.dialect.Labels;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Runs <em>contract SQL</em> — the 2-way SQL a deployment supplies to satisfy a framework contract,
 * as distinct from the SQL an application writes for its own routes and the SQL the framework
 * writes for its own stores (docs/contract-sql-execution.md).
 *
 * <p>Identity contracts and SCIM contracts each grew their own executor, and the two diverged from
 * the route pipeline and from each other on everything that happens after rendering. What all three
 * genuinely share is not the path but the statement: render the 2-way SQL, take a connection,
 * prepare, bind, <b>bound it by a timeout</b>, execute, read the rows under the dialect's label
 * rules, and turn a {@link SQLException} into something the caller can answer with. That sequence
 * is this class.
 *
 * <p>The bound is the point. A route's statement has been cancelled after
 * {@code tesseraql.sql.timeoutSeconds} for as long as the key has existed, precisely so a runaway
 * query cannot hold a pooled connection forever; a sign-in's identity contract and a provisioning
 * call's SCIM contract ran with no bound at all. The same key bounds them, because there is no
 * argument for a sign-in being allowed to run longer than a page.
 *
 * <p>Instances are immutable and cheap; {@link #dialect(String)} and {@link #timeoutSeconds(int)}
 * return a new one.
 */
public final class ContractStatement {

    /**
     * The statement timeout applied when a caller declares none — the same default of 30 seconds
     * {@code tesseraql.sql.timeoutSeconds} carries, so an unwired caller is bounded rather than
     * unbounded.
     */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final DataSource dataSource;
    private final String dialect;
    private final int timeoutSeconds;

    private ContractStatement(DataSource dataSource, String dialect, int timeoutSeconds) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
    }

    /** Contract SQL against {@code dataSource}, bounded by {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public static ContractStatement on(DataSource dataSource) {
        return new ContractStatement(dataSource, null, DEFAULT_TIMEOUT_SECONDS);
    }

    /**
     * The same executor reading result labels under {@code dialect}'s rules.
     *
     * <p>Left unset, labels come back exactly as the driver reports them. That is the right answer
     * for a contract whose aliases are quoted mixed case — SCIM's are, because its attribute names
     * are camelCase — and the wrong one for a contract written in unquoted lower case against
     * Oracle, which folds those to upper case.
     */
    public ContractStatement dialect(String dialect) {
        return new ContractStatement(dataSource, dialect, timeoutSeconds);
    }

    /** The same executor bounded by {@code seconds}; an explicit {@code 0} removes the bound. */
    public ContractStatement timeoutSeconds(int seconds) {
        return new ContractStatement(dataSource, dialect, seconds);
    }

    /** Executes a read contract and returns its rows, keyed by the contract's own aliases. */
    public List<Map<String, Object>> query(String contract, String sql,
            Map<String, Object> params) throws ContractSqlException {
        BoundSql bound = SqlRenderer.render(sql, params);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, bound)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return readRows(resultSet);
            }
        } catch (SQLException ex) {
            throw failed(contract, ex);
        }
    }

    /** Executes a read contract and returns its first row, or {@code null} when it returns none. */
    public Map<String, Object> queryOne(String contract, String sql, Map<String, Object> params)
            throws ContractSqlException {
        List<Map<String, Object>> rows = query(contract, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Executes a write contract and returns the number of rows it affected. */
    public int update(String contract, String sql, Map<String, Object> params)
            throws ContractSqlException {
        BoundSql bound = SqlRenderer.render(sql, params);
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, bound)) {
            return statement.executeUpdate();
        } catch (SQLException ex) {
            throw failed(contract, ex);
        }
    }

    /** Prepares the rendered statement, binds its parameters, and applies the bound. */
    private PreparedStatement prepare(Connection connection, BoundSql bound) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(bound.sql());
        try {
            if (timeoutSeconds > 0) {
                statement.setQueryTimeout(timeoutSeconds);
            }
            List<BoundParameter> parameters = bound.parameters();
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i).value());
            }
            return statement;
        } catch (SQLException | RuntimeException ex) {
            // The try-with-resources that would have closed it has not begun yet: without this a
            // failure to bind leaks the statement, and with it the connection behind it.
            try {
                statement.close();
            } catch (SQLException closing) {
                ex.addSuppressed(closing);
            }
            throw ex;
        }
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(Labels.normalize(dialect, metaData.getColumnLabel(col)),
                        resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }

    /** A driver's answer, named by the contract that asked and classified for the caller. */
    private static ContractSqlException failed(String contract, SQLException ex) {
        return new ContractSqlException(contract, ex);
    }
}
