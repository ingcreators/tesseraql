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
 * <p>All the JDBC row readers ask here now — routes, commands, and the batch executor's
 * query/spool/chunk readers — so there is one answer to change.
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
