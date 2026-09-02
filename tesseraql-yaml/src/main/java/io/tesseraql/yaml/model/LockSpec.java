package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A command route's declared optimistic lock (docs/edit-conflict.md decision 1): the column whose
 * value a caller must send back unchanged for the write to apply.
 *
 * <p>Written either as the bare column — {@code lock: version} — or as a block that also names the
 * column's type:
 *
 * <pre>{@code
 * lock: { column: version, type: integer }
 * }</pre>
 *
 * <p>The type is what the framework needs and all it needs. A form value always arrives as a
 * string, so an untyped lock on a numeric column would send {@code "3"} to an integer comparison
 * and the driver would refuse it — while the same route's JSON leg worked, because its number
 * arrived as a number. The type is declared rather than inferred from the column's name, because
 * this codebase does not infer a column's declared knowledge from its spelling anywhere else
 * ({@code columns:} and detail {@code fields:} take an explicit {@code domain:} for the same
 * reason).
 *
 * @param column the lock column; identifier-checked at route build time, because it is
 *               interpolated into the statement's text
 * @param type   the column's type for coercing the submitted value, or null for an opaque lock
 *               compared exactly as it arrived
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LockSpec(String column, String type) {

    /** {@code lock: version} — the bare column, compared opaquely. */
    @JsonCreator
    public static LockSpec of(String column) {
        return new LockSpec(column, null);
    }
}
