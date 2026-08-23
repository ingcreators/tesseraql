package io.tesseraql.core.sql;

import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.core.dialect.Labels;
import io.tesseraql.core.telemetry.NoopTracer;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.Tracer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * prepare (asking for declared generated keys), bind, <b>bound it by a timeout</b>, execute, read
 * the rows under the declared label policy, and turn a {@link SQLException} into something the
 * caller can answer with. That sequence is this class.
 *
 * <p>The bound is the point. A route's statement has been cancelled after
 * {@code tesseraql.sql.timeoutSeconds} for as long as the key has existed, precisely so a runaway
 * query cannot hold a pooled connection forever; a sign-in's identity contract and a provisioning
 * call's SCIM contract ran with no bound at all. The same key bounds them, because there is no
 * argument for a sign-in being allowed to run longer than a page.
 *
 * <p>Instances are immutable and cheap; {@link #dialect(String)}, {@link #timeoutSeconds(int)} and
 * {@link #rawLabels()} return a new one.
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
    private final boolean rawLabels;
    private final Tracer tracer;

    private ContractStatement(DataSource dataSource, String dialect, int timeoutSeconds,
            boolean rawLabels, Tracer tracer) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.rawLabels = rawLabels;
        this.tracer = tracer == null ? NoopTracer.INSTANCE : tracer;
    }

    /** Contract SQL against {@code dataSource}, bounded by {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public static ContractStatement on(DataSource dataSource) {
        return new ContractStatement(dataSource, null, DEFAULT_TIMEOUT_SECONDS, false,
                NoopTracer.INSTANCE);
    }

    /**
     * The same executor under {@code dialect}'s rules: result labels normalize the way every other
     * executor's do, and a declared generated-key column reaches the JDBC call the dialect's
     * driver honours (docs/contract-sql-execution.md structural decision 2).
     *
     * <p>Left unset, labels come back exactly as the driver reports them and generated keys use
     * {@link Statement#RETURN_GENERATED_KEYS}.
     */
    public ContractStatement dialect(String dialect) {
        return new ContractStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer);
    }

    /** The same executor bounded by {@code seconds}; an explicit {@code 0} removes the bound. */
    public ContractStatement timeoutSeconds(int seconds) {
        return new ContractStatement(dataSource, dialect, seconds, rawLabels, tracer);
    }

    /**
     * The same executor opening one {@code tesseraql.sql.execute} span per statement, carrying
     * {@code surface=contract} and the contract's name (docs/contract-sql-execution.md
     * structural decision 5) — so a slow sign-in or provisioning call stops being an
     * unexplained gap in its trace. Absent a tracer, spans are a no-op.
     */
    public ContractStatement tracer(Tracer tracer) {
        return new ContractStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer);
    }

    /**
     * The same executor reading labels exactly as the driver reports them, whatever the dialect —
     * the declared label policy for a contract whose aliases are quoted mixed case
     * (docs/contract-sql-execution.md structural decision 7). SCIM's are, because its attribute
     * names are camelCase, and a quoted alias passes Oracle's folding untouched; the dialect keeps
     * steering the generated-key branch, which is about the driver, not the labels.
     */
    public ContractStatement rawLabels() {
        return new ContractStatement(dataSource, dialect, timeoutSeconds, true, tracer);
    }

    /**
     * A write's answer: the rows it affected, and the declared generated keys by declared name —
     * empty when the caller declared none or the driver handed none back.
     */
    public record WriteResult(int affectedRows, Map<String, Object> keys) {
    }

    /** Executes a read contract and returns its rows, keyed by the contract's own aliases. */
    public List<Map<String, Object>> query(String contract, String sql,
            Map<String, Object> params) throws ContractSqlException {
        try (Connection connection = dataSource.getConnection()) {
            return query(connection, contract, sql, params);
        } catch (SQLException ex) {
            throw classified(contract, ex);
        }
    }

    /** As {@link #query(String, String, Map)}, on the caller's own connection. */
    public List<Map<String, Object>> query(Connection connection, String contract, String sql,
            Map<String, Object> params) throws ContractSqlException {
        BoundSql bound = SqlRenderer.render(sql, params);
        Span span = started(contract, "query");
        try (PreparedStatement statement = prepare(connection, bound, List.of());
                ResultSet resultSet = statement.executeQuery()) {
            List<Map<String, Object>> rows = readRows(resultSet);
            span.attribute("rowCount", rows.size());
            return rows;
        } catch (SQLException ex) {
            ContractSqlException named = classified(contract, ex);
            span.recordError(named);
            throw named;
        } finally {
            span.end();
        }
    }

    /** Executes a read contract and returns its first row, or {@code null} when it returns none. */
    public Map<String, Object> queryOne(String contract, String sql, Map<String, Object> params)
            throws ContractSqlException {
        List<Map<String, Object>> rows = query(contract, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** As {@link #queryOne(String, String, Map)}, on the caller's own connection. */
    public Map<String, Object> queryOne(Connection connection, String contract, String sql,
            Map<String, Object> params) throws ContractSqlException {
        List<Map<String, Object>> rows = query(connection, contract, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Executes a write contract and returns the number of rows it affected. */
    public int update(String contract, String sql, Map<String, Object> params)
            throws ContractSqlException {
        return update(contract, sql, params, List.of()).affectedRows();
    }

    /** As {@link #update(String, String, Map)}, on the caller's own connection. */
    public int update(Connection connection, String contract, String sql,
            Map<String, Object> params) throws ContractSqlException {
        return update(connection, contract, sql, params, List.of()).affectedRows();
    }

    /**
     * Executes a write contract that declares the columns its store assigns
     * (docs/contract-sql-execution.md structural decision 2) — the same concept a command step
     * declares as {@code sql.keys:}. The statement is a plain write; the driver hands the
     * assigned values back, which is what {@code insert … returning} was standing in for on the
     * two dialects that have it.
     */
    public WriteResult update(String contract, String sql, Map<String, Object> params,
            List<String> keys) throws ContractSqlException {
        try (Connection connection = dataSource.getConnection()) {
            return update(connection, contract, sql, params, keys);
        } catch (SQLException ex) {
            throw classified(contract, ex);
        }
    }

    /** As {@link #update(String, String, Map, List)}, on the caller's own connection. */
    public WriteResult update(Connection connection, String contract, String sql,
            Map<String, Object> params, List<String> keys) throws ContractSqlException {
        BoundSql bound = SqlRenderer.render(sql, params);
        Span span = started(contract, "update");
        try (PreparedStatement statement = prepare(connection, bound, keys)) {
            int affected = statement.executeUpdate();
            Map<String, Object> generated = keys.isEmpty()
                    ? Map.of()
                    : readGeneratedKeys(statement, keys);
            span.attribute("affectedRows", affected);
            return new WriteResult(affected, generated);
        } catch (SQLException ex) {
            ContractSqlException named = classified(contract, ex);
            span.recordError(named);
            throw named;
        } finally {
            span.end();
        }
    }

    /** A contract write that must do several things at once, run inside {@link #transact}. */
    public interface TransactionalBody<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code body} on one connection in one transaction (docs/contract-sql-execution.md
     * structural decision 4) — the shape the command processor uses for a command's steps: open,
     * run, commit; roll back on any failure, so the statements land together or not at all.
     *
     * <p>{@code contract} names the transaction for a failure that happens outside any single
     * statement — taking the connection, committing; a statement's own failure keeps the name
     * that statement was given.
     */
    public <T> T transact(String contract, TransactionalBody<T> body)
            throws ContractSqlException {
        try (Connection connection = dataSource.getConnection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = body.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    ex.addSuppressed(rollback);
                }
                throw ex;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException ex) {
            throw classified(contract, ex);
        }
    }

    /** Prepares the rendered statement, binds its parameters, and applies the bound. */
    private PreparedStatement prepare(Connection connection, BoundSql bound, List<String> keys)
            throws SQLException {
        // Per dialect capability: PostgreSQL/Oracle honour requested key columns; MySQL and
        // SQL Server only hand back the auto-increment/identity value. Oracle without the
        // column names answers with a ROWID, which is why the branch exists.
        PreparedStatement statement = keys.isEmpty()
                ? connection.prepareStatement(bound.sql())
                : generatedKeyColumns()
                        ? connection.prepareStatement(bound.sql(), keys.toArray(String[]::new))
                        : connection.prepareStatement(bound.sql(),
                                Statement.RETURN_GENERATED_KEYS);
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

    private boolean generatedKeyColumns() {
        return Dialect.fromId(dialect)
                .map(known -> known.capabilities().generatedKeyColumns())
                .orElse(false);
    }

    /** Reads the first generated-key row, mapping declared names by label, then by position. */
    private static Map<String, Object> readGeneratedKeys(PreparedStatement statement,
            List<String> keys) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            if (!resultSet.next()) {
                return values;
            }
            ResultSetMetaData metaData = resultSet.getMetaData();
            Map<String, Integer> byLabel = new LinkedHashMap<>();
            for (int col = 1; col <= metaData.getColumnCount(); col++) {
                byLabel.put(metaData.getColumnLabel(col).toLowerCase(Locale.ROOT), col);
            }
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                Integer column = byLabel.get(key.toLowerCase(Locale.ROOT));
                if (column == null && i < metaData.getColumnCount()) {
                    column = i + 1;
                }
                if (column != null) {
                    // The lookup stays case-insensitive: it matches a *declared* key, not a
                    // label a binding will read, so dialect normalization does not apply here.
                    values.put(key,
                            io.tesseraql.core.dialect.ResultRows.value(
                                    resultSet.getObject(column)));
                }
            }
        }
        return values;
    }

    private List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                String label = metaData.getColumnLabel(col);
                row.put(rawLabels ? label : Labels.normalize(dialect, label),
                        resultSet.getObject(col));
            }
            rows.add(row);
        }
        return rows;
    }

    /** One statement's span: the shared name, the contract surface, the contract's own name. */
    private Span started(String contract, String mode) {
        return tracer.start("tesseraql.sql.execute")
                .attribute("surface", "contract")
                .attribute("sqlId", contract)
                .attribute("mode", mode);
    }

    /** A driver's answer, named by the contract that asked and classified for the caller. */
    private static ContractSqlException classified(String contract, SQLException ex) {
        return ex instanceof ContractSqlException already
                ? already
                : new ContractSqlException(contract, ex);
    }
}
