package io.tesseraql.core.files;

/**
 * A file value the declared column type or the row contract could not accept, carrying
 * <em>which</em> column, <em>what</em> text, and <em>why</em> as three separate things
 * (docs/csv-import.md decision 4).
 *
 * <p>Three, because a report groups by reason. The exception's own message names all of them —
 * "Column 'qty': 'abc' is not a number" — which is right for a log line and wrong as a group
 * key: folding the rejected value into the reason makes every bad row its own reason, and a
 * hundred bad numbers in one column become a hundred groups of one. The {@link #complaint} is
 * the reason without the value, and the column and the value ride to the report's own Field and
 * Value columns instead of being re-read out of an English sentence.
 *
 * <p>It stays an {@link IllegalArgumentException} so every existing catch keeps working — the
 * per-row handler treats a bad value as a row error, not as a broken import, and that judgement
 * is unchanged.
 */
public final class ColumnValueException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String column;
    private final String value;
    private final String complaint;

    /**
     * @param complaint why the value was refused, with neither the column nor the value in it
     *                  ("is not a number") — those are the other two components
     */
    public ColumnValueException(String column, String value, String complaint) {
        super("Column '" + column + "': " + (value == null ? "" : "'" + value + "' ") + complaint);
        this.column = column;
        this.value = value;
        this.complaint = complaint;
    }

    /** The declared column name — the bind name, which is what a report entry addresses. */
    public String column() {
        return column;
    }

    /** The text as it stood in the file, so the report can quote what was rejected. */
    public String value() {
        return value;
    }

    /** The reason alone, which is what a report groups on. */
    public String complaint() {
        return complaint;
    }
}
