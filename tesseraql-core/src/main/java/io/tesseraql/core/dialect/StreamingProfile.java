package io.tesseraql.core.dialect;

/**
 * How to stream a large result set on a given dialect (design ch. 42, 28): the JDBC fetch size and
 * whether auto-commit must be disabled for the driver to use a server-side cursor instead of buffering
 * the whole result in memory.
 *
 * @param fetchSize     the JDBC fetch size (MySQL uses {@link Integer#MIN_VALUE} for row streaming)
 * @param autoCommitOff true when the driver only streams with auto-commit disabled (PostgreSQL)
 */
public record StreamingProfile(int fetchSize, boolean autoCommitOff) {
}
