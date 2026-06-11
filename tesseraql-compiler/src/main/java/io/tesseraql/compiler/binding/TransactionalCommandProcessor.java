package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.dialect.Dialect;
import io.tesseraql.core.dialect.SqlErrorKind;
import io.tesseraql.core.dialect.SqlErrors;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.outbox.OutboxStore;
import io.tesseraql.core.sequence.DocumentSequences;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.security.Principal;
import io.tesseraql.yaml.model.ErrorsSpec;
import io.tesseraql.yaml.model.OutboxSpec;
import io.tesseraql.yaml.model.SqlBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Executes a command's SQL steps and its outbox event in one transaction (design ch. 39.2,
 * roadmap Phase 18): one business operation, not one statement.
 *
 * <p>Steps run in their authored order on a single connection. Each step publishes its result
 * into the execution context under {@code steps.<name>} — affected rows, captured generated
 * keys ({@code steps.<name>.keys.<column>}), query rows, or an allocated document-sequence
 * value ({@code steps.<name>.value}) — so later steps and the response can bind it. On any
 * failure the transaction rolls back: every step, sequence allocation, and the event happen
 * together or not at all.
 *
 * <p>The canonical audit binds {@code /* audit.user *}{@code /} and {@code /* audit.now *}{@code /}
 * resolve from the authenticated principal and one clock reading per command, so audit columns
 * stay explicit in the SQL. Declared row-count expectations turn silent lost updates into
 * {@code 409 Conflict} with a usable hint, and declared constraint mappings turn unique /
 * foreign-key violations into field-level error payloads.
 */
public final class TransactionalCommandProcessor implements Processor {

    private static final TqlErrorCode TX_ERROR = new TqlErrorCode(TqlDomain.SQL, 2600);
    private static final TqlErrorCode NO_STORE = new TqlErrorCode(TqlDomain.SQL, 2601);
    /** TQL-SQL-2602: a row-count expectation failed with onMismatch: error. */
    private static final TqlErrorCode EXPECT_FAILED = new TqlErrorCode(TqlDomain.SQL, 2602);
    /** TQL-SQL-2611: a sequence step needs the runtime's DocumentSequences bean. */
    private static final TqlErrorCode NO_SEQUENCES = new TqlErrorCode(TqlDomain.SQL, 2611);
    /** TQL-CAMEL-3102: the route's steps declaration is invalid (fail fast at startup). */
    private static final TqlErrorCode INVALID_STEPS = new TqlErrorCode(TqlDomain.CAMEL, 3102);
    /** TQL-SQL-4092: a row-count expectation failed, reported as an optimistic-lock conflict. */
    private static final TqlErrorCode EXPECT_CONFLICT = new TqlErrorCode(TqlDomain.SQL, 4092);
    // Portable constraint-violation codes, mapped to HTTP statuses by ErrorResponseRenderer.
    private static final TqlErrorCode UNIQUE_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4090);
    private static final TqlErrorCode FK_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4091);
    private static final TqlErrorCode NOT_NULL_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4001);
    private static final TqlErrorCode CHECK_VIOLATION = new TqlErrorCode(TqlDomain.SQL, 4002);
    private static final TqlErrorCode SERIALIZATION = new TqlErrorCode(TqlDomain.SQL, 4093);

    /** The reserved bind namespace for the canonical audit binds. */
    private static final String AUDIT = "audit";

    private final String routeId;
    private final List<Step> steps;
    private final boolean singleSql;
    private final String datasourceName;
    private final OutboxEvents outboxEvents;
    private final ErrorsSpec errors;
    private final boolean generatedKeyColumns;

    /**
     * A compiled step: a parsed 2-way SQL statement or a managed sequence allocation.
     *
     * @param contextKey where the result is published: {@code sql} for the single-statement
     *                   form, the step name (under {@code steps.}) otherwise
     */
    private record Step(String name, String contextKey, List<SqlNode> nodes, String sourcePath,
            String mode, Map<String, String> params, List<String> keys, SqlBinding.Expect expect,
            String sequence) {

        boolean isSequence() {
            return sequence != null;
        }
    }

    /**
     * Builds the processor for a command route.
     *
     * @param sql      the single-statement {@code sql:} binding, or null when steps are declared
     * @param stepFile resolves a step's SQL file reference to its (dialect-resolved) path
     */
    public TransactionalCommandProcessor(String routeId, SqlBinding sql,
            Map<String, SqlBinding> declaredSteps,
            java.util.function.Function<String, Path> stepFile,
            String datasourceName, String dialect, OutboxSpec outbox, ErrorsSpec errors,
            String appName) {
        this.routeId = routeId;
        this.datasourceName = datasourceName;
        this.outboxEvents = outbox == null ? null : new OutboxEvents(outbox, appName);
        this.errors = errors == null ? new ErrorsSpec(null) : errors;
        this.generatedKeyColumns = Dialect.fromId(dialect)
                .map(d -> d.capabilities().generatedKeyColumns())
                .orElse(true);
        if (sql != null && !declaredSteps.isEmpty()) {
            throw invalid("declare either sql: or steps:, not both");
        }
        if (sql == null && declaredSteps.isEmpty()) {
            throw invalid("a command route needs a sql: or steps: declaration");
        }
        this.singleSql = sql != null;
        this.steps = compile(sql, declaredSteps, stepFile);
    }

    private List<Step> compile(SqlBinding sql, Map<String, SqlBinding> declaredSteps,
            java.util.function.Function<String, Path> stepFile) {
        Map<String, SqlBinding> bindings = singleSql
                ? Map.of("sql", sql)
                : declaredSteps;
        List<Step> compiled = new ArrayList<>();
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, SqlBinding> entry : bindings.entrySet()) {
            String name = entry.getKey();
            SqlBinding binding = entry.getValue();
            validate(name, binding, seen);
            String contextKey = singleSql ? "sql" : name;
            if (binding.isSequence()) {
                compiled.add(new Step(name, contextKey, null, null, "sequence",
                        binding.params(), List.of(), null, binding.sequence()));
            } else {
                Path file = stepFile.apply(binding.file());
                // Steps default to update (commands write); the single-statement form keeps its
                // pre-Phase-18 semantics: an outbox command always executes as an update, a
                // plain command honors the binding's effective mode (query by default).
                String mode = singleSql
                        ? (outboxEvents != null ? "update" : binding.effectiveMode())
                        : (binding.mode() == null || binding.mode().isBlank()
                                ? "update"
                                : binding.mode());
                // Expectations and key capture count affected rows, which query mode never has.
                if ("query".equals(mode)
                        && (binding.expect() != null || !binding.keys().isEmpty())) {
                    throw invalid("step '" + name + "': expect/keys need an update statement -"
                            + " declare mode: update");
                }
                compiled.add(new Step(name, contextKey, Sql2WayParser.parse(read(file)),
                        file.toString(), mode,
                        binding.params(), binding.keys(), binding.expect(), null));
            }
            seen.add(name);
        }
        return List.copyOf(compiled);
    }

    /** Fail-fast validation of one step declaration (runs at route build time). */
    private void validate(String name, SqlBinding binding, java.util.Set<String> earlier) {
        if (binding.isContract() || binding.isService()) {
            throw invalid("step '" + name + "': contract/service bindings are not supported in"
                    + " command steps - use a SQL file or a sequence");
        }
        if (binding.isSequence() == (binding.file() != null)) {
            throw invalid("step '" + name + "' must declare exactly one of file: or sequence:");
        }
        if (binding.isSequence() && (!binding.keys().isEmpty() || binding.expect() != null)) {
            throw invalid("step '" + name + "': keys/expect do not apply to a sequence step");
        }
        if (binding.expect() != null && binding.expect().rows() == null) {
            throw invalid("step '" + name + "': expect.rows is required");
        }
        if (binding.expect() != null
                && !List.of("conflict", "error").contains(binding.expect().effectiveOnMismatch())) {
            throw invalid("step '" + name + "': expect.onMismatch must be conflict or error");
        }
        if (binding.params().containsKey(AUDIT)) {
            throw invalid("step '" + name + "': the bind name 'audit' is reserved for the"
                    + " canonical audit binds");
        }
        for (Map.Entry<String, String> param : binding.params().entrySet()) {
            String expr = param.getValue();
            if (expr != null && expr.startsWith("steps.")) {
                String referenced = expr.split("\\.")[1];
                if (!earlier.contains(referenced)) {
                    throw invalid("step '" + name + "': param '" + param.getKey()
                            + "' references step '" + referenced
                            + "' which is not an earlier step");
                }
            }
        }
    }

    private TqlException invalid(String message) {
        return new TqlException(INVALID_STEPS, "Route '" + routeId + "': " + message);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void process(Exchange exchange) throws Exception {
        Map<String, Object> context = exchange.getProperty(TesseraqlProperties.CONTEXT, Map.class);
        if (context == null) {
            context = new java.util.HashMap<>();
            exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        }
        // One clock reading per command, so every audit.now bind in the transaction agrees.
        Map<String, Object> audit = auditBinds(context);
        context.put(AUDIT, audit);
        Map<String, Object> stepResults = new LinkedHashMap<>();
        context.put("steps", stepResults);

        DataSource dataSource = exchange.getContext().getRegistry()
                .lookupByNameAndType(datasourceName, DataSource.class);
        OutboxStore store = outboxEvents == null
                ? null
                : exchange.getContext().getRegistry().lookupByNameAndType(
                        TesseraqlProperties.OUTBOX_STORE_BEAN, OutboxStore.class);
        if (outboxEvents != null && store == null) {
            throw new TqlException(NO_STORE, "Outbox store is not configured");
        }

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                for (Step step : steps) {
                    Map<String, Object> result = step.isSequence()
                            ? allocateSequence(exchange, connection, step)
                            : executeSql(exchange, connection, step, context, audit);
                    stepResults.put(step.name(), result);
                    if (singleSql) {
                        context.put(step.contextKey(), result);
                    }
                }
                if (store != null) {
                    String eventId = store.insert(connection, outboxEvents.build(context));
                    context.put("outbox", Map.of("eventId", eventId));
                    if (singleSql) {
                        // Compatibility: the single-statement form exposes sql.eventId.
                        ((Map<String, Object>) context.get("sql")).put("eventId", eventId);
                    }
                }
                connection.commit();
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw asTqlException(ex);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        }
        exchange.getMessage().setBody(singleSql ? context.get("sql") : Map.copyOf(stepResults));
    }

    /** The canonical audit binds: the caller's identity and one clock reading (roadmap Phase 18). */
    private static Map<String, Object> auditBinds(Map<String, Object> context) {
        Object principal = context.get("principal");
        String user = null;
        if (principal instanceof Principal p) {
            user = p.loginId() != null ? p.loginId() : p.subject();
        }
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("user", user);
        audit.put("now", java.sql.Timestamp.from(Instant.now()));
        return audit;
    }

    private Map<String, Object> allocateSequence(Exchange exchange, Connection connection,
            Step step) {
        DocumentSequences sequences = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.DOCUMENT_SEQUENCES_BEAN,
                        DocumentSequences.class);
        if (sequences == null) {
            throw new TqlException(NO_SEQUENCES, "Route '" + routeId
                    + "': document sequences are not configured in this runtime");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", sequences.next(connection, step.sequence()));
        return result;
    }

    private Map<String, Object> executeSql(Exchange exchange, Connection connection, Step step,
            Map<String, Object> context, Map<String, Object> audit) throws SQLException {
        // Params resolve against the live context, so a later step binds earlier results
        // (steps.header.keys.id) the same way it binds request fields.
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        step.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        params.put(AUDIT, audit);
        BoundSql bound = SqlRenderer.render(step.nodes(), params);

        long startNanos = System.nanoTime();
        long startedAt = System.currentTimeMillis();
        Map<String, Object> result = "query".equals(step.mode())
                ? executeQuery(connection, bound)
                : executeUpdate(connection, bound, step);
        recordExecution(exchange, step, result, startNanos, startedAt);

        if (step.expect() != null) {
            checkExpectation(step, (Integer) result.get("affectedRows"));
        }
        return result;
    }

    private Map<String, Object> executeUpdate(Connection connection, BoundSql bound, Step step)
            throws SQLException {
        try (PreparedStatement statement = prepare(connection, bound.sql(), step.keys())) {
            bind(statement, bound);
            int affected = statement.executeUpdate();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("affectedRows", affected);
            if (!step.keys().isEmpty()) {
                result.put("keys", readGeneratedKeys(statement, step.keys()));
            }
            return result;
        }
    }

    private PreparedStatement prepare(Connection connection, String sql, List<String> keys)
            throws SQLException {
        if (keys.isEmpty()) {
            return connection.prepareStatement(sql);
        }
        // Per dialect capability: PostgreSQL/Oracle honor requested key columns; MySQL and
        // SQL Server only hand back the auto-increment/identity value.
        return generatedKeyColumns
                ? connection.prepareStatement(sql, keys.toArray(String[]::new))
                : connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    /** Reads the first generated-key row, mapping declared names by label, then by position. */
    private static Map<String, Object> readGeneratedKeys(PreparedStatement statement,
            List<String> keys) throws SQLException {
        Map<String, Object> values = new LinkedHashMap<>();
        try (ResultSet resultSet = statement.getGeneratedKeys()) {
            if (!resultSet.next()) {
                return values;
            }
            java.sql.ResultSetMetaData metaData = resultSet.getMetaData();
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
                    values.put(key, resultSet.getObject(column));
                }
            }
        }
        return values;
    }

    private static Map<String, Object> executeQuery(Connection connection, BoundSql bound)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            bind(statement, bound);
            try (ResultSet resultSet = statement.executeQuery()) {
                java.sql.ResultSetMetaData metaData = resultSet.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (resultSet.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int col = 1; col <= metaData.getColumnCount(); col++) {
                        row.put(metaData.getColumnLabel(col).toLowerCase(Locale.ROOT),
                                resultSet.getObject(col));
                    }
                    rows.add(row);
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("rowCount", rows.size());
                return result;
            }
        }
    }

    private static void bind(PreparedStatement statement, BoundSql bound) throws SQLException {
        for (int i = 0; i < bound.parameters().size(); i++) {
            statement.setObject(i + 1, bound.parameters().get(i).value());
        }
    }

    /** Turns a row-count mismatch into a conflict (or error) instead of a silent lost update. */
    private void checkExpectation(Step step, Integer affected) {
        int actual = affected == null ? 0 : affected;
        if (actual == step.expect().rows()) {
            return;
        }
        boolean conflict = "conflict".equals(step.expect().effectiveOnMismatch());
        throw TqlException.builder(conflict ? EXPECT_CONFLICT : EXPECT_FAILED)
                .message("Step '" + step.name() + "' affected " + actual + " row(s), expected "
                        + step.expect().rows())
                .source(step.sourcePath())
                .details(Map.of("conflict", Map.of(
                        "step", step.name(),
                        "expectedRows", step.expect().rows(),
                        "actualRows", actual,
                        "hint", "The record may have been changed or deleted by another user;"
                                + " reload it and retry the operation")))
                .build();
    }

    /** Classifies a failure, applying the route's declared constraint-to-field mapping. */
    private TqlException asTqlException(Exception ex) {
        if (ex instanceof TqlException tql) {
            return tql;
        }
        SQLException sql = ex instanceof SQLException direct
                ? direct
                : (ex.getCause() instanceof SQLException cause ? cause : null);
        if (sql == null) {
            return new TqlException(TX_ERROR, "Command transaction failed: " + ex.getMessage(),
                    ex);
        }
        SqlErrorKind kind = SqlErrors.classify(sql);
        TqlException.Builder builder = TqlException.builder(code(kind))
                .message("Command transaction failed: " + sql.getMessage())
                .cause(ex);
        List<Map<String, Object>> fields = mapConstraints(sql, kind);
        if (!fields.isEmpty()) {
            builder.details(Map.of("fields", fields));
        }
        return builder.build();
    }

    /** Matches declared constraint names against the violation, yielding field-level errors. */
    private List<Map<String, Object>> mapConstraints(SQLException ex, SqlErrorKind kind) {
        if (errors.constraints().isEmpty()) {
            return List.of();
        }
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> fields = new ArrayList<>();
        errors.constraints().forEach((constraint, mapping) -> {
            if (message.contains(constraint.toLowerCase(Locale.ROOT))) {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("field", mapping.field());
                field.put("code", mapping.code() != null ? mapping.code() : defaultCode(kind));
                field.put("constraint", constraint);
                fields.add(field);
            }
        });
        return fields;
    }

    private static String defaultCode(SqlErrorKind kind) {
        return switch (kind) {
            case UNIQUE_VIOLATION -> "duplicate";
            case FOREIGN_KEY_VIOLATION -> "invalid-reference";
            case NOT_NULL_VIOLATION -> "required";
            case CHECK_VIOLATION -> "invalid";
            default -> "constraint-violation";
        };
    }

    private static TqlErrorCode code(SqlErrorKind kind) {
        return switch (kind) {
            case UNIQUE_VIOLATION -> UNIQUE_VIOLATION;
            case FOREIGN_KEY_VIOLATION -> FK_VIOLATION;
            case NOT_NULL_VIOLATION -> NOT_NULL_VIOLATION;
            case CHECK_VIOLATION -> CHECK_VIOLATION;
            case SERIALIZATION_FAILURE -> SERIALIZATION;
            default -> TX_ERROR;
        };
    }

    private void recordExecution(Exchange exchange, Step step, Map<String, Object> result,
            long startNanos, long startedAt) {
        io.tesseraql.core.diag.SqlExecutionLog log = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.SLOW_SQL_LOG_BEAN,
                        io.tesseraql.core.diag.SqlExecutionLog.class);
        if (log == null) {
            return;
        }
        Object count = result.containsKey("affectedRows")
                ? result.get("affectedRows")
                : result.get("rowCount");
        long rows = count instanceof Number number ? number.longValue() : 0L;
        log.record(new io.tesseraql.core.diag.SqlExecution(step.sourcePath(), step.mode(),
                (System.nanoTime() - startNanos) / 1_000_000L, rows, startedAt));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
