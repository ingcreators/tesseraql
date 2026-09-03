package io.tesseraql.core.validation;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.ScopeResolver;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.sql.SqlStatement;
import java.sql.Connection;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * A compiled set of declarative validation rules (roadmap Phase 19).
 *
 * <p>A rule is either a cross-field expression (whitelist-only, design ch. 8.1; truthy means
 * valid) or a validation SQL statement: a SELECT whose returned rows are the violations —
 * uniqueness, existence, balance checks — executed on the caller's connection so it can ride
 * the command's transaction. Rules are compiled once (expressions parsed, SQL parsed, shape
 * checked) and evaluated per request; all rules run and every violation is collected, so the
 * caller can report the complete picture in one response.
 *
 * <p>Each violation is a client-safe map carrying the stable error model: the rule id, a field
 * path, a rule code (defaulting to the rule id), and an optional message key. A SQL rule's
 * result columns named {@code field}, {@code code}, or {@code message} override the declared
 * defaults per row; any other selected column rides along, so a balance check can return the
 * offending line number or amount. The author of the SELECT decides what is exposed.
 */
public final class ValidationRules {

    /** TQL-FIELD-2003: an invalid validation rule declaration (fails fast at build time). */
    public static final TqlErrorCode INVALID_RULE = new TqlErrorCode(TqlDomain.FIELD, 2003);

    private final List<Rule> rules;

    /**
     * A compiled rule: exactly one of {@code expression} (with {@code sql} null) or {@code sql}
     * (the parsed 2-way SELECT) is present.
     */
    public record Rule(String id, Expr when, Expr expression, List<SqlNode> sql, String sourcePath,
            Map<String, String> params, String field, String code, String message) {

        public Rule {
            params = params == null ? Map.of() : Map.copyOf(params);
            sql = sql == null ? null : List.copyOf(sql);
        }
    }

    public ValidationRules(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Compiles a cross-field expression rule; the expression must hold for the input to be valid. */
    public static Rule expression(String id, String when, String rule, String field, String code,
            String message) {
        return expression(id, when, rule, field, code, message,
                ExpressionFunctions.processDefault());
    }

    /** Compiles a cross-field expression rule against the given function set. */
    public static Rule expression(String id, String when, String rule, String field, String code,
            String message, ExpressionFunctions functions) {
        require(id, rule != null && !rule.isBlank(), "an expression rule needs a rule:");
        require(id, field != null && !field.isBlank(), "a field: to report violations against is"
                + " required");
        return new Rule(id, guard(when, functions), ExpressionParser.parse(rule, functions),
                null, null, Map.of(), field, effectiveCode(id, code), message);
    }

    /** Compiles a validation SQL rule; each row the SELECT returns is reported as a violation. */
    public static Rule sql(String id, String when, String sqlText, String sourcePath,
            Map<String, String> params, String field, String code, String message) {
        return sql(id, when, sqlText, sourcePath, params, field, code, message,
                ExpressionFunctions.processDefault());
    }

    /** Compiles a validation SQL rule against the given function set. */
    public static Rule sql(String id, String when, String sqlText, String sourcePath,
            Map<String, String> params, String field, String code, String message,
            ExpressionFunctions functions) {
        require(id, field != null && !field.isBlank(), "a field: to report violations against is"
                + " required");
        require(id, isSelect(sqlText), "validation SQL must be a SELECT returning violations -"
                + " it runs inside the command's transaction and must not write");
        return new Rule(id, guard(when, functions), null,
                Sql2WayParser.parse(sqlText, functions), sourcePath, params, field,
                effectiveCode(id, code), message);
    }

    /**
     * Whether the statement's first keyword (past leading whitespace and comments) starts a
     * SELECT or a WITH clause. Shared with lint so both report the same verdict.
     */
    public static boolean isSelect(String sqlText) {
        if (sqlText == null) {
            return false;
        }
        String head = statementBody(sqlText).toLowerCase(Locale.ROOT);
        return head.startsWith("select") || head.startsWith("with");
    }

    /**
     * The statement past its leading whitespace and comments — the text whose first keyword is
     * the verb. Shared with lint, which tested the raw text and so read every generated file's
     * own {@code -- tesseraql-scaffold-checksum:} header as the statement: its verb test answered
     * no for every file this framework writes, and a clause keyword in that header read as a
     * clause.
     */
    public static String statementBody(String sqlText) {
        return sqlText == null ? "" : stripLeadingCommentsAndWhitespace(sqlText);
    }

    /**
     * Evaluates every rule against the context, collecting all violations; {@code observer} sees
     * each rendered SQL rule so callers can record coverage traces (design ch. 14).
     *
     * <p>{@code scopeResolver} expands a {@code /*%scope … *&#47;} directive in a rule's SQL
     * (docs/data-scoping.md). A validation rule reads rows to decide whether a write is legal, so
     * it has to read them through the caller's own scope — otherwise the rule answers from rows
     * the caller cannot see. Callers with no request principal pass
     * {@link ScopeResolver#UNSUPPORTED}, which rejects the directive loudly.
     *
     * <p>{@code statements} is the statement layer each SQL rule runs through
     * (docs/contract-sql-execution.md structural decision 1): the caller's declared bound, tracer
     * and span parent ride it, and each rule's statement executes bounded, classified and spanned
     * ({@code surface=validation}) like every other declared statement. There is deliberately no
     * overload that defaults it: a caller that wants no bound writes {@code timeoutSeconds(0)}
     * where everyone can see it.
     */
    public List<Map<String, Object>> evaluate(Map<String, Object> context, Connection connection,
            ScopeResolver scopeResolver, SqlStatement statements,
            BiConsumer<Rule, BoundSql> observer) throws SQLException {
        EvaluationContext evaluation = new EvaluationContext(context);
        SqlStatement ruleStatements = statements == null ? null : statements.surface("validation");
        List<Map<String, Object>> violations = new ArrayList<>();
        for (Rule rule : rules) {
            if (rule.when() != null && !rule.when().evalBoolean(evaluation)) {
                continue;
            }
            if (rule.expression() != null) {
                if (!rule.expression().evalBoolean(evaluation)) {
                    violations.add(violation(rule));
                }
            } else {
                violations.addAll(executeSql(rule, evaluation, context, connection, scopeResolver,
                        ruleStatements, observer));
            }
        }
        return violations;
    }

    private static List<Map<String, Object>> executeSql(Rule rule, EvaluationContext evaluation,
            Map<String, Object> scopeContext, Connection connection, ScopeResolver scopeResolver,
            SqlStatement statements, BiConsumer<Rule, BoundSql> observer) throws SQLException {
        Map<String, Object> params = new LinkedHashMap<>();
        rule.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        // Ambient principal.* binds (docs/ambient-params.md); declared params win by name.
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        BoundSql bound = SqlRenderer.render(rule.sql(), params, scopeResolver,
                scopeContext);
        if (observer != null) {
            observer.accept(rule, bound);
        }
        return statements.read(connection,
                rule.sourcePath() == null ? rule.id() : rule.sourcePath(), bound,
                (resultSet, span) -> {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    List<Map<String, Object>> violations = new ArrayList<>();
                    while (resultSet.next()) {
                        Map<String, Object> violation = violation(rule);
                        for (int col = 1; col <= metaData.getColumnCount(); col++) {
                            Object value = resultSet.getObject(col);
                            if (value != null) {
                                // Lower-cased by contract, not by dialect: the columns that
                                // override a violation's defaults (field, code, message) match
                                // by this spelling on every database.
                                violation.put(metaData.getColumnLabel(col)
                                        .toLowerCase(Locale.ROOT), value);
                            }
                        }
                        violations.add(violation);
                    }
                    span.attribute("rowCount", violations.size());
                    return violations;
                });
    }

    /** The declared defaults of a violation; a SQL rule's row columns override them. */
    private static Map<String, Object> violation(Rule rule) {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("rule", rule.id());
        violation.put("field", rule.field());
        violation.put("code", rule.code());
        if (rule.message() != null && !rule.message().isBlank()) {
            violation.put("message", rule.message());
        }
        return violation;
    }

    private static Expr guard(String when, ExpressionFunctions functions) {
        return when == null || when.isBlank() ? null : ExpressionParser.parse(when, functions);
    }

    private static String effectiveCode(String id, String code) {
        return code == null || code.isBlank() ? id : code;
    }

    private static void require(String id, boolean condition, String message) {
        if (!condition) {
            throw new TqlException(INVALID_RULE,
                    "Validation rule '" + id + "': " + message);
        }
    }

    private static String stripLeadingCommentsAndWhitespace(String sql) {
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (sql.startsWith("--", i)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? sql.length() : eol + 1;
            } else if (sql.startsWith("/*", i)) {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 2;
            } else {
                break;
            }
        }
        return sql.substring(i);
    }
}
