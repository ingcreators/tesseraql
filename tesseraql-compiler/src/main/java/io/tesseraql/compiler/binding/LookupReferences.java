package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.SqlIdentifiers;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.core.sql.SqlStatement;
import io.tesseraql.yaml.model.InputField;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The keyed fetch behind a {@code lookup:} field (docs/reference-lookup.md decisions 2 and 3):
 * the referenced route's own SQL, re-rendered and wrapped in a code- or id-equality derived
 * table — so what a resolve or a submit-time existence check can find is exactly what the
 * search route's author lets this caller see, scopes and 2-way arms included.
 *
 * <p>Shared by the synthesized resolve companion route and the transactional command's
 * existence check: one algorithm, so direct entry, row picking and submit validation can never
 * disagree about whether a key exists.
 */
public final class LookupReferences {

    /** TQL-FIELD-4623: a lookup: field's declaration cannot compile against the manifest. */
    public static final TqlErrorCode INVALID_LOOKUP = new TqlErrorCode(TqlDomain.FIELD, 4623);

    /** TQL-VIEW-3329: a lookup source row lacks a declared column (id, code: or label:). */
    public static final TqlErrorCode MISSING_COLUMN = new TqlErrorCode(TqlDomain.VIEW, 3329);

    private LookupReferences() {
    }

    /**
     * One field's compiled lookup: the referenced route's parsed SQL and connection facts,
     * resolved at build time so a dangling reference fails the boot, not a keystroke.
     */
    public record Compiled(String field, InputField.LookupSpec spec, List<SqlNode> nodes,
            String sourcePath, Map<String, String> params, String datasource, String dialect) {

        public Compiled {
            requireIdentifier(field);
            requireIdentifier(spec.code());
            requireIdentifier(spec.label());
        }

        private static void requireIdentifier(String name) {
            if (name == null || !SqlIdentifiers.isIdentifier(name)) {
                throw new TqlException(INVALID_LOOKUP, "Lookup column '" + name
                        + "' is not a legal column name");
            }
        }
    }

    /**
     * The rows matching {@code keyColumn = keyValue}, at most two — one is a resolution, zero
     * or several are not, and nothing past the second row changes that answer. {@code context}
     * supplies the referenced route's declared binds (absent ones fold their 2-way arms away)
     * and the ambient {@code principal.*} binds its scopes and SQL may read.
     */
    public static List<Map<String, Object>> fetch(Compiled lookup, SqlStatement statements,
            Connection connection, io.tesseraql.core.sql.ScopeResolver scopes,
            Map<String, Object> context, String keyColumn, Object keyValue)
            throws io.tesseraql.core.sql.SqlStatementException {
        if (!SqlIdentifiers.isIdentifier(keyColumn)) {
            throw new TqlException(INVALID_LOOKUP, "Lookup column '" + keyColumn
                    + "' is not a legal column name");
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> params = new LinkedHashMap<>();
        lookup.params().forEach((bindName, sourceExpr) -> params.put(bindName,
                evaluation.resolve(Arrays.asList(sourceExpr.split("\\.")))));
        io.tesseraql.core.sql.AmbientBinds.seed(params, evaluation);
        BoundSql bound = SqlRenderer.render(lookup.nodes(), params, scopes, context);
        String sql = "select * from (" + stripTrailingOrderBy(bound.sql()) + ") tql_lookup"
                + " where " + keyColumn + " = ?";
        List<Object> values = new ArrayList<>();
        bound.parameters().forEach(parameter -> values.add(parameter.value()));
        values.add(keyValue);
        return statements.read(connection, lookup.sourcePath(), sql, values,
                (resultSet, span) -> {
                    List<Map<String, Object>> rows = new ArrayList<>();
                    java.sql.ResultSetMetaData meta = resultSet.getMetaData();
                    while (rows.size() < 2 && resultSet.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= meta.getColumnCount(); i++) {
                            row.put(meta.getColumnLabel(i), resultSet.getObject(i));
                        }
                        rows.add(row);
                    }
                    span.attribute("rowCount", rows.size());
                    return rows;
                });
    }

    /**
     * A declared column's value in a source row, tolerating the dialect's label case-folding
     * (an unquoted Oracle label answers uppercase); a column the row does not carry at all is
     * the authoring defect {@code TQL-VIEW-3329} names — {@code select *} makes a build-time
     * check a liar, so the refusal is here.
     */
    public static Object column(Compiled lookup, Map<String, Object> row, String name) {
        if (row.containsKey(name)) {
            return row.get(name);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        throw new TqlException(MISSING_COLUMN, "Lookup source " + lookup.spec().source()
                + " rows carry no '" + name + "' column (the declared id, code: and label:"
                + " columns must all be selected)");
    }

    /**
     * The submit-time existence check (docs/reference-lookup.md decision 3): each lookup
     * field's bound id must match exactly one source row, on the command's own connection —
     * the currency a validation SQL rule spends. An absent value is not checked here;
     * required-ness stays the binder's business.
     */
    public static List<Map<String, Object>> violations(List<Compiled> lookups,
            SqlStatement statements, Connection connection,
            io.tesseraql.core.sql.ScopeResolver scopes, Map<String, Object> context)
            throws io.tesseraql.core.sql.SqlStatementException {
        List<Map<String, Object>> violations = new ArrayList<>();
        Object params = context.get("params");
        Map<?, ?> bound = params instanceof Map<?, ?> map ? map : Map.of();
        for (Compiled lookup : lookups) {
            Object value = bound.get(lookup.field());
            if (value == null || String.valueOf(value).isBlank()) {
                continue;
            }
            List<Map<String, Object>> rows = fetch(lookup, statements, connection, scopes,
                    context, lookup.field(), value);
            if (rows.size() != 1) {
                Map<String, Object> violation = new LinkedHashMap<>();
                violation.put("field", lookup.field());
                violation.put("code", "invalid-reference");
                violation.put("message", "tql.constraint.invalid-reference");
                violations.add(violation);
            }
        }
        return violations;
    }

    /**
     * The rendered SQL without its trailing top-level {@code ORDER BY} — a resolve wants one
     * row, not an order, and SQL Server refuses an ordered derived table. Depth- and
     * literal-aware so an {@code order by} inside a subquery, a window frame, or a string
     * survives; only a last clause at nesting depth zero is cut.
     */
    static String stripTrailingOrderBy(String sql) {
        int depth = 0;
        boolean inString = false;
        int cut = -1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            // Rendered SQL keeps plain remarks; an "order by" inside one is not a clause.
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = sql.indexOf('\n', i);
                i = end < 0 ? sql.length() : end;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? sql.length() : end + 1;
                continue;
            }
            switch (c) {
                case '\'' -> inString = true;
                case '(' -> depth++;
                case ')' -> depth--;
                default -> {
                    if (depth == 0 && (c == 'o' || c == 'O') && startsWord(sql, i)
                            && sql.regionMatches(true, i, "order", 0, 5)
                            && followedByBy(sql, i + 5)) {
                        cut = i;
                    }
                }
            }
        }
        return cut < 0 ? sql : sql.substring(0, cut).stripTrailing();
    }

    /** Whether position {@code i} begins a word (not the tail of an identifier). */
    private static boolean startsWord(String sql, int i) {
        if (i == 0) {
            return true;
        }
        char prev = sql.charAt(i - 1);
        return !Character.isLetterOrDigit(prev) && prev != '_';
    }

    /** Whether whitespace and the keyword {@code by} follow position {@code i}. */
    private static boolean followedByBy(String sql, int i) {
        int at = i;
        if (at >= sql.length() || !Character.isWhitespace(sql.charAt(at))) {
            return false;
        }
        while (at < sql.length() && Character.isWhitespace(sql.charAt(at))) {
            at++;
        }
        return sql.regionMatches(true, at, "by", 0, 2)
                && (at + 2 >= sql.length() || !Character.isLetterOrDigit(sql.charAt(at + 2)));
    }
}
