package io.tesseraql.core.dialect;

/**
 * How a JDBC row becomes a bindable map: the column label a response binding writes, and the
 * value it reads.
 *
 * <p>There were two answers. The SQL producer normalized labels per dialect and converted JDBC
 * temporals to ISO-8601 strings; a command's query-steps force-lowercased every label and passed
 * {@code java.sql.Timestamp} straight through. So the same {@code select … as "orderTotal"} came
 * back as {@code orderTotal} on a query route and {@code ordertotal} inside a command on Oracle,
 * and a date rendered as {@code 2026-07-25} on one path and as whatever {@code toString()} a
 * driver's temporal happens to have on the other. A response binding written against one path
 * broke on the other, and nothing said why.
 *
 * <p>The policy, stated as a rule rather than as a count of who follows it: a reader that hands
 * rows straight to a response binding asks here for BOTH halves. The route reader, the command
 * readers, the contract reader behind {@code SqlStatement.query} and the workflow transition
 * reader all do, so one store answers the same column the same way whichever path read it.
 *
 * <p>Two deliberate departures, each with the same reason — the rows are consumed by a later step
 * that binds them, and an ISO-8601 string is not a timestamp. The batch executor's step, keyset
 * and chunk readers keep values typed (docs/sql-execution-shapes.md structural decision 1), and so
 * does the enrichment reader, whose columns are composed into rows a writer binds. Both ask here
 * for the label and answer the value themselves.
 *
 * <p>Said plainly because a sweeping claim stood here and was false: this class does not know its
 * callers and cannot enforce the rule. Readers outside the framework's own row paths — the
 * reference lookup's, the declarative suite's and Studio's — do not ask here at all.
 */
public final class ResultRows {

    private ResultRows() {
    }

    /**
     * The bindable form of a column label: dialect-normalized, so a quoted mixed-case alias
     * survives on Oracle while an unquoted identifier the driver upper-cased comes back lower.
     */
    public static String label(String dialect, String columnLabel) {
        return Labels.normalize(dialect, columnLabel);
    }

    /** JDBC temporals as ISO-8601 strings, so JSON output does not depend on the driver. */
    public static Object value(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value;
    }
}
