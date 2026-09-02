package io.tesseraql.core.files;

/**
 * A file value the declared column type could not accept, carrying <em>which</em> column and
 * <em>what</em> text (docs/csv-import.md decision 4).
 *
 * <p>The message has always named both, because a rejection nobody can locate is not a
 * rejection. What was missing is the structure: a report that wants a `Row` / `Field` /
 * `Message` table cannot get the field back out of an English sentence, and the review pass
 * exists to render exactly that table. Same failure, same message, now addressable.
 *
 * <p>It stays an {@link IllegalArgumentException} so every existing catch keeps working — the
 * per-row handler treats a bad value as a row error, not as a broken import, and that judgement
 * is unchanged.
 */
public final class ColumnValueException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String column;
    private final String value;

    public ColumnValueException(String column, String value, String message) {
        super(message);
        this.column = column;
        this.value = value;
    }

    /** The declared column name — the bind name, which is what a report entry addresses. */
    public String column() {
        return column;
    }

    /** The text as it stood in the file, so the report can quote what was rejected. */
    public String value() {
        return value;
    }
}
