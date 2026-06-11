package io.tesseraql.core.dialect;

/**
 * A portable, dialect-independent classification of a database error (design ch. 42), derived from
 * its SQLState and vendor code. Lets callers react to the <em>meaning</em> of a failure (e.g. a
 * unique-key conflict) without hard-coding each database's codes.
 */
public enum SqlErrorKind {

    /** A unique or primary-key constraint was violated. */
    UNIQUE_VIOLATION,
    /** A foreign-key constraint was violated. */
    FOREIGN_KEY_VIOLATION,
    /** A NOT NULL constraint was violated. */
    NOT_NULL_VIOLATION,
    /** A CHECK constraint was violated. */
    CHECK_VIOLATION,
    /** An integrity constraint was violated but the specific kind is unknown. */
    INTEGRITY_CONSTRAINT,
    /** A serialization failure or deadlock; the transaction may succeed if retried. */
    SERIALIZATION_FAILURE,
    /** The error could not be classified. */
    UNKNOWN
}
