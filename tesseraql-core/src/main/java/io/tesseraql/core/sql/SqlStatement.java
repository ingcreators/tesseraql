package io.tesseraql.core.sql;

import io.tesseraql.core.dialect.Dialect;
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
 * The one statement primitive (docs/contract-sql-execution.md structural decision 1): where
 * rendered SQL meets JDBC. First extracted for <em>contract SQL</em> — the 2-way SQL a deployment
 * supplies to satisfy a framework contract — and generalized in slice 7 so every executor of
 * declared SQL is a caller; the {@code surface} attribute says which one.
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
public final class SqlStatement {

    private static final System.Logger LOG = System.getLogger(SqlStatement.class.getName());

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
    private final String surface;
    private final io.tesseraql.core.telemetry.SpanContext spanParent;
    private final int fetchSize;
    private final Map<String, Object> attributes;

    private SqlStatement(DataSource dataSource, String dialect, int timeoutSeconds,
            boolean rawLabels, Tracer tracer, String surface,
            io.tesseraql.core.telemetry.SpanContext spanParent, int fetchSize,
            Map<String, Object> attributes) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.rawLabels = rawLabels;
        this.tracer = tracer == null ? NoopTracer.INSTANCE : tracer;
        this.surface = surface == null ? "contract" : surface;
        this.spanParent = spanParent;
        // Integer.MIN_VALUE is MySQL/MariaDB's row-streaming signal (StreamingProfiles), not a
        // negative to clamp; folding it to 0 silently buffers a whole export in the driver.
        this.fetchSize = fetchSize == Integer.MIN_VALUE ? fetchSize : Math.max(0, fetchSize);
        this.attributes = attributes == null ? Map.of() : attributes;
    }

    /** Statements against {@code dataSource}, bounded by {@link #DEFAULT_TIMEOUT_SECONDS}. */
    public static SqlStatement on(DataSource dataSource) {
        return new SqlStatement(dataSource, null, DEFAULT_TIMEOUT_SECONDS, false,
                NoopTracer.INSTANCE, "contract", null, 0, Map.of());
    }

    /**
     * Statements that run only on caller-supplied connections — the shape of an executor that
     * rides someone else's transaction (a command's, a suite runner's) and never opens its own.
     * Only the connection-taking forms work; a form that would open a connection refuses.
     */
    public static SqlStatement onCallerConnections() {
        return new SqlStatement(null, null, DEFAULT_TIMEOUT_SECONDS, false,
                NoopTracer.INSTANCE, "contract", null, 0, Map.of());
    }

    /** The data source, where this executor owns its connections; refuses where it does not. */
    private DataSource ownConnections() {
        if (dataSource == null) {
            throw new IllegalStateException("This SqlStatement runs only on caller-supplied"
                    + " connections (onCallerConnections()) - use the connection-taking forms");
        }
        return dataSource;
    }

    /**
     * The same executor under {@code dialect}'s rules: result labels normalize the way every other
     * executor's do, and a declared generated-key column reaches the JDBC call the dialect's
     * driver honours (docs/contract-sql-execution.md structural decision 2).
     *
     * <p>Left unset, labels come back exactly as the driver reports them and generated keys use
     * {@link Statement#RETURN_GENERATED_KEYS}.
     */
    public SqlStatement dialect(String dialect) {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                spanParent, fetchSize, attributes);
    }

    /** The same executor bounded by {@code seconds}; an explicit {@code 0} removes the bound. */
    public SqlStatement timeoutSeconds(int seconds) {
        return new SqlStatement(dataSource, dialect, seconds, rawLabels, tracer, surface,
                spanParent, fetchSize, attributes);
    }

    /** The declared bound, for callers that thread it onward (e.g. into decision lookups). */
    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * The same executor stamping {@code surface} on its spans (docs/contract-sql-execution.md
     * structural decision 5): which kind of caller this statement serves —
     * {@code route} | {@code command} | {@code job} | {@code chunk} | {@code contract} |
     * {@code validation} | {@code workflow} | {@code transfer} | {@code enrich} |
     * {@code decision}.
     */
    public SqlStatement surface(String surface) {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                spanParent, fetchSize, attributes);
    }

    /** The same executor parenting its spans on {@code parent} (null roots a new trace). */
    public SqlStatement spanParent(io.tesseraql.core.telemetry.SpanContext parent) {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                parent, fetchSize, attributes);
    }

    /**
     * The same executor opening one {@code tesseraql.sql.execute} span per statement, carrying
     * {@code surface=contract} and the contract's name (docs/contract-sql-execution.md
     * structural decision 5) — so a slow sign-in or provisioning call stops being an
     * unexplained gap in its trace. Absent a tracer, spans are a no-op.
     */
    public SqlStatement tracer(Tracer tracer) {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                spanParent, fetchSize, attributes);
    }

    /**
     * The same executor reading labels exactly as the driver reports them, whatever the dialect —
     * the declared label policy for a contract whose aliases are quoted mixed case
     * (docs/contract-sql-execution.md structural decision 7). SCIM's are, because its attribute
     * names are camelCase, and a quoted alias passes Oracle's folding untouched; the dialect keeps
     * steering the generated-key branch, which is about the driver, not the labels.
     */
    public SqlStatement rawLabels() {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, true, tracer, surface,
                spanParent, fetchSize, attributes);
    }

    /**
     * The same executor preparing its reads forward-only and read-only, fetching {@code rows} at
     * a time — the streaming shape a spooling export needs so the driver cursors through the
     * result instead of buffering it whole (docs/contract-sql-execution.md slice 7; the caller
     * picks the size from its dialect's streaming profile). {@code 0} keeps the driver's default
     * prepare; {@link Integer#MIN_VALUE} passes through as MySQL/MariaDB's row-streaming signal.
     * Reads only; the generated-key prepare of a write is unaffected.
     */
    public SqlStatement fetchSize(int rows) {
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                spanParent, rows, attributes);
    }

    /**
     * The same executor stamping {@code key} on every span it opens, beside {@code surface} and
     * the statement's own name (docs/sql-execution-shapes.md structural decision 2) — the seam
     * for a caller whose spans carry identity the primitive cannot know, like a job step's
     * {@code stepId}.
     */
    public SqlStatement attribute(String key, Object value) {
        Map<String, Object> stamped = new LinkedHashMap<>(attributes);
        stamped.put(key, value);
        return new SqlStatement(dataSource, dialect, timeoutSeconds, rawLabels, tracer, surface,
                spanParent, fetchSize, Map.copyOf(stamped));
    }

    /**
     * A write's answer: the rows it affected, and the declared generated keys by declared name —
     * empty when the caller declared none or the driver handed none back.
     */
    public record WriteResult(int affectedRows, Map<String, Object> keys) {
    }

    /** Executes a write contract and returns the number of rows it affected. */
    public int update(String contract, String sql, Map<String, Object> params)
            throws SqlStatementException {
        return update(contract, sql, params, List.of()).affectedRows();
    }

    /** As {@link #update(String, String, Map)}, on the caller's own connection. */
    public int update(Connection connection, String contract, String sql,
            Map<String, Object> params) throws SqlStatementException {
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
            List<String> keys) throws SqlStatementException {
        try (Connection connection = ownConnections().getConnection()) {
            return update(connection, contract, sql, params, keys);
        } catch (SQLException ex) {
            throw classified(contract, ex);
        }
    }

    /** As {@link #update(String, String, Map, List)}, on the caller's own connection. */
    public WriteResult update(Connection connection, String contract, String sql,
            Map<String, Object> params, List<String> keys) throws SqlStatementException {
        return update(connection, contract, SqlRenderer.render(sql, params), keys);
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
            throws SqlStatementException {
        try (Connection connection = ownConnections().getConnection()) {
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
                try {
                    connection.setAutoCommit(previous);
                } catch (SQLException restore) {
                    // The transaction is already committed or rolled back; a restore that
                    // fails here would otherwise replace that outcome with a failure the
                    // caller acts on — a committed SCIM create re-reported as a 500 invites
                    // the retry that duplicates it. The pool retires the sick connection.
                    LOG.log(System.Logger.Level.WARNING,
                            "Could not restore autocommit after transaction {0}: {1}", contract,
                            restore.getMessage());
                }
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
        PreparedStatement statement = !keys.isEmpty()
                ? generatedKeyColumns()
                        ? connection.prepareStatement(bound.sql(), keys.toArray(String[]::new))
                        : connection.prepareStatement(bound.sql(),
                                Statement.RETURN_GENERATED_KEYS)
                : fetchSize != 0
                        ? connection.prepareStatement(bound.sql(), ResultSet.TYPE_FORWARD_ONLY,
                                ResultSet.CONCUR_READ_ONLY)
                        : connection.prepareStatement(bound.sql());
        try {
            if (timeoutSeconds > 0) {
                statement.setQueryTimeout(timeoutSeconds);
            }
            if (fetchSize != 0 && keys.isEmpty()) {
                statement.setFetchSize(fetchSize);
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

    /** A caller-owned read over the statement's open {@link ResultSet} — spooling, capping,
     * shaping rows its own way — while this class owns prepare, bind, bound, classify and span. */
    public interface ResultSetReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }

    /**
     * A caller-owned read that can also stamp what only it knows — its row count, a truncation —
     * onto the statement's span; the span's name, identity and lifecycle stay this class's.
     */
    public interface SpannedReader<T> {
        T read(ResultSet resultSet, Span span) throws SQLException;
    }

    /**
     * The caller's answer to the first row past a capped read's cap
     * ({@link #rows(int, RowOverflow)}): return to truncate the read — the caller
     * has already said so, e.g. with a warn log — or throw its refusal.
     *
     * <p>The refusal is unchecked, and this method declares no {@code throws} so that it cannot
     * be anything else. A refusal thrown as a {@link SQLException} is classified into a
     * {@link SqlStatementException} — itself a {@code SQLException} — and is then
     * indistinguishable, in a caller's {@code catch (SQLException)}, from the database refusing
     * the statement. One caller reads that catch as "this feature is not installed" and degrades
     * to an empty answer, so a read too large to materialize would widen a permission set
     * instead of refusing it. {@link #read(Connection, String, BoundSql, SpannedReader)} rethrows
     * a {@code RuntimeException} unchanged, which is the arm a refusal travels on.
     */
    public interface RowOverflow {
        void onRowPastCap();
    }

    /**
     * The row past the cap of an uncapped read — unreachable, because {@code -1} never reaches a
     * cap. Named rather than spelled as an empty lambda at each site, so an uncapped read reads as
     * a decision instead of an omission.
     */
    private static final RowOverflow UNCAPPED = () -> {
    };

    /**
     * A capped, materializing read, shaped by THIS statement: labels under its own label policy —
     * normalized for its dialect, or the driver's own when it was built {@link #rawLabels()} — and
     * values through {@link io.tesseraql.core.dialect.ResultRows#value(Object)}, so a JDBC temporal
     * is an ISO-8601 string. Up to {@code maxRows} rows ({@code -1} is uncapped), the row past the
     * cap answered by {@code onOverflow}. Stamps the row count on the statement's span.
     *
     * <p>It reads the statement's own dialect rather than taking one, because the two could
     * disagree; and it is an instance member rather than a static factory because a static one
     * cannot see {@code rawLabels}, which is why the executors that declare that policy could
     * never use the capped read at all and materialized without a bound instead.
     */
    public SpannedReader<List<Map<String, Object>>> rows(int maxRows, RowOverflow onOverflow) {
        return (resultSet, span) -> {
            List<Map<String, Object>> rows = materialize(resultSet, maxRows, onOverflow);
            span.attribute("rowCount", rows.size());
            return rows;
        };
    }

    /**
     * As {@link #rows(int, RowOverflow)} with no cap — for a read whose size the caller has
     * already bounded, or has deliberately chosen not to.
     */
    public SpannedReader<List<Map<String, Object>>> rows() {
        return rows(-1, UNCAPPED);
    }

    /**
     * The first row, or {@code null} when the statement returns none — and it stops there rather
     * than materializing the rest to discard it.
     */
    public SpannedReader<Map<String, Object>> firstRow() {
        return (resultSet, span) -> {
            List<Map<String, Object>> rows = materialize(resultSet, 1, UNCAPPED);
            span.attribute("rowCount", rows.size());
            return rows.isEmpty() ? null : rows.get(0);
        };
    }

    /** The one row-shaping loop every read in this class goes through. */
    private List<Map<String, Object>> materialize(ResultSet resultSet, int maxRows,
            RowOverflow onOverflow) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            if (maxRows >= 0 && rows.size() >= maxRows) {
                onOverflow.onRowPastCap();
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                String label = metaData.getColumnLabel(col);
                row.put(rawLabels
                        ? label
                        : io.tesseraql.core.dialect.ResultRows.label(dialect, label),
                        io.tesseraql.core.dialect.ResultRows.value(resultSet.getObject(col)));
            }
            rows.add(row);
        }
        return rows;
    }

    /**
     * Executes a caller-rendered statement and hands its {@link ResultSet} to {@code reader} —
     * the seam for executors whose reads stream or cap rather than materialize
     * (docs/contract-sql-execution.md slice 7).
     */
    public <T> T read(Connection connection, String sqlId, BoundSql bound,
            ResultSetReader<T> reader) throws SqlStatementException {
        return read(connection, sqlId, bound, (resultSet, span) -> reader.read(resultSet));
    }

    /** As {@link #read(Connection, String, BoundSql, SpannedReader)}, on a connection of this
     * executor's own — the shape of a route statement, one connection per statement. */
    public <T> T read(String sqlId, BoundSql bound, SpannedReader<T> reader)
            throws SqlStatementException {
        try (Connection connection = ownConnections().getConnection()) {
            return read(connection, sqlId, bound, reader);
        } catch (SQLException ex) {
            throw classified(sqlId, ex);
        }
    }

    /** As {@link #update(Connection, String, BoundSql)}, on a connection of this executor's own. */
    public int update(String sqlId, BoundSql bound) throws SqlStatementException {
        try (Connection connection = ownConnections().getConnection()) {
            return update(connection, sqlId, bound);
        } catch (SQLException ex) {
            throw classified(sqlId, ex);
        }
    }

    /** As {@link #read(Connection, String, BoundSql, ResultSetReader)}, with the span in reach. */
    public <T> T read(Connection connection, String sqlId, BoundSql bound,
            SpannedReader<T> reader) throws SqlStatementException {
        Span span = started(sqlId, "query");
        try (PreparedStatement statement = prepare(connection, bound, List.of());
                ResultSet resultSet = statement.executeQuery()) {
            return reader.read(resultSet, span);
        } catch (SQLException ex) {
            SqlStatementException named = classified(sqlId, ex);
            span.recordError(named);
            throw named;
        } catch (RuntimeException ex) {
            // A caller-owned read refusing mid-read (a row cap, a shape check) is this
            // statement's failure too; without this the span ends clean under a refusal.
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /**
     * Executes a framework-built read — plain SQL with positional values, no 2-way render —
     * under the same bound, classification and span as everything else.
     */
    public <T> T read(Connection connection, String sqlId, String sql, List<Object> values,
            SpannedReader<T> reader) throws SqlStatementException {
        Span span = started(sqlId, "query");
        try (PreparedStatement statement = prepareValues(connection, sql, values);
                ResultSet resultSet = statement.executeQuery()) {
            return reader.read(resultSet, span);
        } catch (SQLException ex) {
            SqlStatementException named = classified(sqlId, ex);
            span.recordError(named);
            throw named;
        } catch (RuntimeException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /** Executes a caller-rendered write and returns the rows it affected. */
    public int update(Connection connection, String sqlId, BoundSql bound)
            throws SqlStatementException {
        return update(connection, sqlId, bound, List.of()).affectedRows();
    }

    /**
     * Executes a caller-rendered write that declares the columns its store assigns
     * (docs/contract-sql-execution.md structural decision 2).
     */
    public WriteResult update(Connection connection, String sqlId, BoundSql bound,
            List<String> keys) throws SqlStatementException {
        Span span = started(sqlId, "update");
        try (PreparedStatement statement = prepare(connection, bound, keys)) {
            // execute + getUpdateCount rather than executeUpdate: a statement can do work and
            // hand back a result rather than a count, and a driver may refuse executeUpdate for
            // it — DuckDB does, for DuckLake's maintenance calls. -1 is the honest "no count".
            statement.execute();
            int affected = statement.getUpdateCount();
            Map<String, Object> generated = keys.isEmpty()
                    ? Map.of()
                    : readGeneratedKeys(statement, keys);
            span.attribute("affectedRows", affected);
            return new WriteResult(affected, generated);
        } catch (SQLException ex) {
            SqlStatementException named = classified(sqlId, ex);
            span.recordError(named);
            throw named;
        } finally {
            span.end();
        }
    }

    /**
     * Executes a framework-built statement — plain SQL with positional values, no 2-way render —
     * under the same bound, classification and span as everything else.
     */
    public int update(Connection connection, String sqlId, String sql, List<Object> values)
            throws SqlStatementException {
        Span span = started(sqlId, "update");
        try (PreparedStatement statement = prepareValues(connection, sql, values)) {
            statement.execute();
            int affected = statement.getUpdateCount();
            span.attribute("affectedRows", affected);
            return affected;
        } catch (SQLException ex) {
            SqlStatementException named = classified(sqlId, ex);
            span.recordError(named);
            throw named;
        } finally {
            span.end();
        }
    }

    /** Prepares a plain statement, binds its positional values, and applies the bound. */
    private PreparedStatement prepareValues(Connection connection, String sql, List<Object> values)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            if (timeoutSeconds > 0) {
                statement.setQueryTimeout(timeoutSeconds);
            }
            for (int i = 0; i < values.size(); i++) {
                statement.setObject(i + 1, values.get(i));
            }
            return statement;
        } catch (SQLException | RuntimeException ex) {
            // As in prepare(): a failure to bind must not leak the statement.
            try {
                statement.close();
            } catch (SQLException closing) {
                ex.addSuppressed(closing);
            }
            throw ex;
        }
    }

    /**
     * A reusable writer over one connection (docs/sql-execution-shapes.md structural
     * decision 5): one prepared statement per rendered-SQL text, cached for the handle's
     * lifetime, so a caller that executes the same statement ten thousand times prepares it
     * once. Two verbs: {@link #execute(BoundSql)} runs one row now; {@link #add(BoundSql)}
     * queues it into a JDBC batch that {@link #flush()} executes.
     *
     * <p>2-way SQL renders per row, so consecutive rows can render different statements.
     * {@code add} preserves row order by <b>flushing the pending batch whenever the incoming
     * row's SQL differs from it</b> — queuing per-variant and executing at the end would
     * reorder writes against the same table. The batch win is real exactly when rows share a
     * shape, which is the common case.
     *
     * <p>A flush is one executed statement and opens one span ({@code mode=batch}, the queued
     * row count and the summed affected count as attributes); a single-row {@code execute}
     * opens none — this handle serves callers that span per phase or per flush, never per row.
     * {@link #close()} with rows still queued refuses: a handle must never drop queued writes
     * on the floor.
     */
    public final class Rows implements AutoCloseable {

        private final Connection connection;
        private final String sqlId;
        private final Map<String, PreparedStatement> prepared = new LinkedHashMap<>();
        private PreparedStatement pending;
        private int pendingRows;

        private Rows(Connection connection, String sqlId) {
            this.connection = connection;
            this.sqlId = sqlId;
        }

        /** Runs one row now, via the cached prepared statement; flushes any pending batch
         * first so mixed use keeps row order. Answers the affected-row count. */
        public int execute(BoundSql bound) throws SqlStatementException {
            flush();
            try {
                PreparedStatement statement = statementFor(bound.sql());
                bindInto(statement, bound);
                statement.execute();
                return statement.getUpdateCount();
            } catch (SQLException ex) {
                throw classified(sqlId, ex);
            }
        }

        /** Queues one row for the next {@link #flush()}, flushing first when its rendered SQL
         * differs from the pending batch's. */
        public void add(BoundSql bound) throws SqlStatementException {
            try {
                PreparedStatement statement = statementFor(bound.sql());
                if (pending != null && pending != statement) {
                    flush();
                }
                bindInto(statement, bound);
                statement.addBatch();
                pending = statement;
                pendingRows++;
            } catch (SQLException ex) {
                throw classified(sqlId, ex);
            }
        }

        /** Executes the pending batch as one statement and answers the summed affected count
         * ({@code SUCCESS_NO_INFO} counts as zero); a no-op when nothing is queued. */
        public int flush() throws SqlStatementException {
            if (pending == null || pendingRows == 0) {
                return 0;
            }
            Span span = started(sqlId, "batch").attribute("batchSize", pendingRows);
            try {
                int[] counts = pending.executeBatch();
                int affected = 0;
                for (int count : counts) {
                    affected += Math.max(count, 0);
                }
                span.attribute("affectedRows", affected);
                return affected;
            } catch (SQLException ex) {
                SqlStatementException named = classified(sqlId, ex);
                span.recordError(named);
                throw named;
            } finally {
                pending = null;
                pendingRows = 0;
                span.end();
            }
        }

        /**
         * Drops the queued rows without executing them — the abort path's explicit answer
         * (the transaction they belonged to is rolling back), so {@link #close()} can stay a
         * refusal on the path that merely forgot to flush.
         */
        public void discard() throws SqlStatementException {
            if (pending != null) {
                try {
                    pending.clearBatch();
                } catch (SQLException ex) {
                    throw classified(sqlId, ex);
                }
            }
            pending = null;
            pendingRows = 0;
        }

        /** Closes the cached statements; refuses — after closing them — when rows were queued
         * and never flushed, because dropping them silently is the one wrong answer. */
        @Override
        public void close() throws SqlStatementException {
            SQLException closing = null;
            for (PreparedStatement statement : prepared.values()) {
                try {
                    statement.close();
                } catch (SQLException ex) {
                    if (closing == null) {
                        closing = ex;
                    } else {
                        closing.addSuppressed(ex);
                    }
                }
            }
            prepared.clear();
            if (pendingRows > 0) {
                int dropped = pendingRows;
                pendingRows = 0;
                pending = null;
                throw new IllegalStateException(dropped + " queued row(s) were never flushed"
                        + " - flush() before close(), or the writes are silently lost");
            }
            if (closing != null) {
                throw classified(sqlId, closing);
            }
        }

        /** The statement for one rendered SQL text, prepared once under the declared bound. */
        private PreparedStatement statementFor(String sql) throws SQLException {
            PreparedStatement cached = prepared.get(sql);
            if (cached != null) {
                return cached;
            }
            PreparedStatement statement = connection.prepareStatement(sql);
            try {
                if (timeoutSeconds > 0) {
                    statement.setQueryTimeout(timeoutSeconds);
                }
            } catch (SQLException | RuntimeException ex) {
                try {
                    statement.close();
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                throw ex;
            }
            prepared.put(sql, statement);
            return statement;
        }

        private void bindInto(PreparedStatement statement, BoundSql bound) throws SQLException {
            for (int i = 0; i < bound.parameters().size(); i++) {
                statement.setObject(i + 1, bound.parameters().get(i).value());
            }
        }
    }

    /**
     * A reusable writer over {@code connection} for the statement the caller names — the
     * prepare-once/execute-many shape a chunk writer has and every one-shot form lacks
     * (docs/sql-execution-shapes.md structural decision 5).
     */
    public Rows rows(Connection connection, String sqlId) {
        return new Rows(connection, sqlId);
    }

    /**
     * Executes a stored call (docs/sql-execution-shapes.md structural decision 7): ordinary
     * rendered 2-way SQL — the driver's call escape or the dialect's native syntax — whose OUT
     * parameters are the bind sites in the reserved {@code out.} namespace. Each rendered
     * parameter whose expression is {@code out.<name>} is registered by position with the JDBC
     * type {@code outTypes} declares for that name; every other parameter binds its value as
     * usual. The OUT values come back by declared name, shaped by
     * {@link io.tesseraql.core.dialect.ResultRows#value} (they land in a response or the
     * context, not in another bind).
     *
     * <p><b>All-or-nothing, both ways, per execution</b> (a 2-way branch can exclude a bind
     * site at render time, so the render is what is checked): a rendered {@code out.*} with no
     * declaration, or a declaration no bind site rendered, refuses naming the mismatch.
     */
    public Map<String, Object> call(Connection connection, String sqlId, BoundSql bound,
            Map<String, Integer> outTypes) throws SqlStatementException {
        Span span = started(sqlId, "call");
        try (java.sql.CallableStatement statement = connection.prepareCall(bound.sql())) {
            if (timeoutSeconds > 0) {
                statement.setQueryTimeout(timeoutSeconds);
            }
            List<BoundParameter> parameters = bound.parameters();
            Map<String, Integer> outPositions = new LinkedHashMap<>();
            for (int i = 0; i < parameters.size(); i++) {
                BoundParameter parameter = parameters.get(i);
                String expression = parameter.expression();
                if (expression != null && expression.startsWith("out.")) {
                    String name = expression.substring("out.".length()).trim();
                    Integer type = outTypes.get(name);
                    if (type == null) {
                        throw new IllegalStateException("Call '" + sqlId + "' renders OUT"
                                + " parameter '" + name + "' that out: does not declare"
                                + " (declared: " + outTypes.keySet() + ")");
                    }
                    statement.registerOutParameter(i + 1, type);
                    outPositions.put(name, i + 1);
                } else {
                    statement.setObject(i + 1, parameter.value());
                }
            }
            if (!outPositions.keySet().equals(outTypes.keySet())) {
                java.util.Set<String> unrendered = new java.util.LinkedHashSet<>(
                        outTypes.keySet());
                unrendered.removeAll(outPositions.keySet());
                throw new IllegalStateException("Call '" + sqlId + "' declares OUT parameter(s) "
                        + unrendered + " that the rendered statement never binds - declare"
                        + " exactly the out.* bind sites the SQL renders");
            }
            statement.execute();
            Map<String, Object> outs = new LinkedHashMap<>();
            for (Map.Entry<String, Integer> position : outPositions.entrySet()) {
                outs.put(position.getKey(), io.tesseraql.core.dialect.ResultRows.value(
                        statement.getObject(position.getValue())));
            }
            return outs;
        } catch (SQLException ex) {
            SqlStatementException named = classified(sqlId, ex);
            span.recordError(named);
            throw named;
        } catch (RuntimeException ex) {
            span.recordError(ex);
            throw ex;
        } finally {
            span.end();
        }
    }

    /** One statement's span: the shared name, the declared surface, the statement's own name. */
    private Span started(String sqlId, String mode) {
        Span span = tracer.start("tesseraql.sql.execute", spanParent)
                .attribute("surface", surface)
                .attribute("sqlId", sqlId)
                .attribute("mode", mode);
        attributes.forEach(span::attribute);
        return span;
    }

    /** A driver's answer, named by the contract that asked and classified for the caller. */
    private static SqlStatementException classified(String contract, SQLException ex) {
        return ex instanceof SqlStatementException already
                ? already
                : new SqlStatementException(contract, ex);
    }
}
