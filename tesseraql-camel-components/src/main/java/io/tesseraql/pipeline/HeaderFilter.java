package io.tesseraql.pipeline;

import java.util.Locale;
import java.util.Set;

/**
 * Which headers cross the boundary between a request and a message
 * (docs/camel-removal.md structural decision 2).
 *
 * <p>Read out of {@code DefaultHeaderFilterStrategy} and {@code HttpHeaderFilterStrategy} rather
 * than out of their documentation, because the part that matters is not mentioned at any call
 * site: <strong>both directions drop every header whose name starts with {@code Camel}</strong>
 * ({@code inFilterStartsWith} and {@code outFilterStartsWith} are both initialised to
 * {@code {"Camel", "camel"}}, with {@code caseInsensitive} on). A replacement written from the call
 * sites alone would have answered every request with a {@code CamelHttpResponseCode: 200} header.
 *
 * <p>The framework's internal names still begin with that prefix ({@link Headers}), which is why
 * the rule is kept as it was rather than reasoned about afresh: the names move in their own slice,
 * and a filter that stopped matching them before they moved would leak them all.
 */
public final class HeaderFilter {

    /**
     * Headers a response must not carry over from the request.
     *
     * <p>The common set, minus {@code cache-control}: this framework sets that deliberately on
     * responses, and the runtime removed it from both filters for exactly that reason.
     * {@code content-type} is here because the edge writes it explicitly — which is the reason a
     * generic copy that stops here leaves a response with no content type at all.
     */
    private static final Set<String> NEVER_LEAVES = Set.of("content-length", "content-type",
            "host", "connection", "date", "pragma");

    private HeaderFilter() {
    }

    /** Whether a message header may be written onto the response. */
    public static boolean leaves(String name) {
        return !internal(name) && !NEVER_LEAVES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Whether a request header may be put onto the message. */
    public static boolean enters(String name) {
        return !internal(name);
    }

    /** The framework's own header names, which belong to the pipeline and not to the wire. */
    private static boolean internal(String name) {
        return name.length() >= 5 && name.regionMatches(true, 0, "Camel", 0, 5);
    }
}
