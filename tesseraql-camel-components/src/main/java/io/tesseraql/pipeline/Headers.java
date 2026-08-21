package io.tesseraql.pipeline;

/**
 * The message headers the framework's own layers agree on (docs/camel-removal.md decision 2).
 *
 * <p>These were {@code org.apache.camel.Exchange}'s constants. The <strong>values</strong> are
 * unchanged deliberately: they are written by the edge and read by binders and renderers across
 * five modules, a handful of places spell them as literals, and changing where a name comes from
 * is a different change from changing what it is. One variable at a time — the campaign's own rule,
 * applied to itself.
 */
public final class Headers {

    /** The response status a renderer sets. */
    public static final String HTTP_RESPONSE_CODE = "CamelHttpResponseCode";

    /** The content type, on the way in and on the way out. */
    public static final String CONTENT_TYPE = "Content-Type";

    /** The request's method. */
    public static final String HTTP_METHOD = "CamelHttpMethod";

    /** The request's normalised path — not the raw one. */
    public static final String HTTP_PATH = "CamelHttpPath";

    /** The request's URI. */
    public static final String HTTP_URI = "CamelHttpUri";

    /** The request's absolute URL. */
    public static final String HTTP_URL = "CamelHttpUrl";

    /** The query string, decoded. */
    public static final String HTTP_QUERY = "CamelHttpQuery";

    /** The query string, as it arrived. */
    public static final String HTTP_RAW_QUERY = "CamelHttpRawQuery";

    /** A polled or uploaded file's name. */
    public static final String FILE_NAME = "CamelFileName";

    private Headers() {
    }
}
