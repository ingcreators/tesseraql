package io.tesseraql.core.spool;

/** The content kind of a spooled temp file (design ch. 28.4 {@code SpoolKind}). */
public enum SpoolKind {
    ROWSET,
    CSV,
    JSONL,
    NDJSON,
    BINARY
}
