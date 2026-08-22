package io.tesseraql.pipeline;

/**
 * The message headers the framework's own layers agree on (docs/camel-removal.md decision 2).
 *
 * <p>These are internal names, not wire names: the edge writes them, binders and renderers across
 * five modules read them, and {@link HeaderFilter} drops every one of them in both directions so
 * that none reaches a client. The {@code tql.} prefix is what the filter matches, and it is a dot
 * rather than a hyphen on purpose — the framework <em>does</em> send hyphenated {@code Tesseraql-}
 * headers between its own nodes ({@code Tesseraql-Acting-Role}), and those have to pass the filter
 * to be read at the far end. A prefix rule that could not tell the two apart would silently
 * disable acting roles.
 */
public final class Headers {

    /** The response status a renderer sets. */
    public static final String HTTP_RESPONSE_CODE = "tql.http.responseCode";

    /** The content type, on the way in and on the way out. */
    public static final String CONTENT_TYPE = "Content-Type";

    /** The request's method. */
    public static final String HTTP_METHOD = "tql.http.method";

    /** The request's normalised path — not the raw one. */
    public static final String HTTP_PATH = "tql.http.path";

    /** The request's URI. */
    public static final String HTTP_URI = "tql.http.uri";

    /** The request's absolute URL. */
    public static final String HTTP_URL = "tql.http.url";

    /** The query string, decoded. */
    public static final String HTTP_QUERY = "tql.http.query";

    /** The query string, as it arrived. */
    public static final String HTTP_RAW_QUERY = "tql.http.rawQuery";

    /** A polled or uploaded file's name. */
    public static final String FILE_NAME = "tql.file.name";

    /** The address the connection arrived on. */
    public static final String LOCAL_ADDRESS = "tql.http.localAddress";

    /**
     * The peer's address — what a role's network condition and the session record resolve
     * against, which is why the edge has to set it on every exchange it builds.
     */
    public static final String REMOTE_ADDRESS = "tql.http.remoteAddress";

    private Headers() {
    }
}
