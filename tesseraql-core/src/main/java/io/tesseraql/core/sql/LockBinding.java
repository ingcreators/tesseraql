package io.tesseraql.core.sql;

/**
 * The optimistic lock a command seeds into its statement's bind map, expanding the
 * {@code /*%lock*}{@code /} directive the authored statement carries (docs/edit-conflict.md
 * decision 2).
 *
 * <p>Deliberately a value, not an SPI. {@link ScopeResolver} and {@link FilePathResolver} are
 * interfaces because a scope predicate and a file root are per-app policy the renderer has to look
 * up; a lock is per-request state the command has already read, so it rides the statement's own
 * bind map the way the {@code audit} binds do.
 *
 * <p>{@code column} is interpolated into the SQL text — the lock compares a column named in YAML,
 * which no bind can do — so it is identifier-checked here as well as at route build time. This
 * constructor is the last boundary before the text is written.
 *
 * @param column the declared lock column, compared and never advanced by the framework
 * @param value  the value the caller sent back; ignored when {@code waived}
 * @param waived whether the caller deliberately waived the comparison, which renders {@code (1=1)}
 */
public record LockBinding(String column, Object value, boolean waived) {

    /** The bind-map key a command seeds its lock under — the request field's own name. */
    public static final String PARAM = "_lock";

    public LockBinding {
        if (!SqlIdentifiers.isIdentifier(column)) {
            throw new IllegalArgumentException(
                    "Lock column '" + column + "' is not a SQL identifier");
        }
    }
}
