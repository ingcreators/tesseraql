package io.tesseraql.pipeline;

/**
 * The header names the framework's own layers agree on.
 *
 * <p>What is left after the request and the response became their own objects
 * (docs/vertx-native.md decision 1): one wire name two directions share. The {@code tql.http.*}
 * metadata names, the peer-address names, and the {@code tql.} filter prefix all described the
 * one-bag message and left with it — request metadata is a typed accessor now, the poll loop's
 * file-name hand-off is the {@code POLLED_FILE_NAME} exchange property, and internal state never
 * shares a namespace with the wire.
 */
public final class Headers {

    /** The content type, on the way in and on the way out. */
    public static final String CONTENT_TYPE = "Content-Type";

    private Headers() {
    }
}
