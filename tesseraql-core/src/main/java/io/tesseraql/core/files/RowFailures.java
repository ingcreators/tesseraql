package io.tesseraql.core.files;

import io.tesseraql.core.dialect.SqlErrorKind;
import io.tesseraql.core.dialect.SqlErrors;
import java.sql.SQLException;

/**
 * The framework's sentence for a row the write pass could not apply (docs/csv-import.md
 * decision 4).
 *
 * <p>A rejected row used to publish the driver's own {@code getMessage()}. That text is the
 * right thing for an operator to read and the wrong thing to put on a page: it names tables,
 * columns and constraints the author never wrote, quotes SQL, and on several dialects quotes
 * the conflicting row's values — another record's data, shown to whoever uploaded the file.
 * The failure <em>class</em> is what the author can act on, and {@link SqlErrors} already
 * derives it portably, so the report speaks the class and the driver text rides on as
 * {@link FileTransferService.RowError#detail()}.
 *
 * <p>Deliberately not localized here. These sentences sit beside the ones
 * {@link ColumnValueException} produces, which are the framework's English too; the surface
 * that renders them is free to key its own catalog off the class, and doing that in core would
 * mean threading a locale through a service whose caller is a background thread with no
 * request.
 */
public final class RowFailures {

    private RowFailures() {
    }

    /**
     * The sentence for whatever the per-row statement threw. Anything that is not a database
     * error — a render failure, a bug — gets the generic sentence rather than its own text,
     * because the same leak argument applies to any message this code did not write.
     */
    public static String message(Throwable failure) {
        SQLException sql = sqlCause(failure);
        return sql == null ? "The row could not be written." : message(SqlErrors.classify(sql));
    }

    /** The sentence for one portable failure class. */
    public static String message(SqlErrorKind kind) {
        return switch (kind) {
            case UNIQUE_VIOLATION -> "A record with these values already exists.";
            case FOREIGN_KEY_VIOLATION -> "This row refers to a record that does not exist.";
            case NOT_NULL_VIOLATION -> "A value this row must supply is missing.";
            case CHECK_VIOLATION -> "A value in this row is outside what the table allows.";
            case INTEGRITY_CONSTRAINT -> "The database refused this row's values.";
            case SERIALIZATION_FAILURE ->
                "The row could not be written because another change held it.";
            case UNKNOWN -> "The database refused this row.";
        };
    }

    /**
     * The driver's own text, for the operator-facing half. Null when the failure carries none,
     * so an absent detail is absent on the wire rather than the string "null".
     */
    public static String detail(Throwable failure) {
        String text = failure == null ? null : failure.getMessage();
        return text == null || text.isBlank() ? null : text;
    }

    /**
     * The first {@link SQLException} in the chain; drivers and pools wrap generously. Depth
     * bounded rather than cycle-checked: a {@code getCause()} override is the only way a chain
     * loops, and a bound handles that without pretending to detect it.
     */
    private static SQLException sqlCause(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql;
            }
        }
        return null;
    }
}
