package io.tesseraql.pipeline;

/**
 * Which headers cross the boundary between a request and a message
 * (docs/camel-removal.md structural decision 2).
 *
 * <p>The rule was read out of {@code DefaultHeaderFilterStrategy} and
 * {@code HttpHeaderFilterStrategy} rather than out of their documentation, because the part that
 * mattered was mentioned at no call site: <strong>both directions dropped every header whose name
 * carries the framework's prefix</strong> ({@code inFilterStartsWith} and
 * {@code outFilterStartsWith} were both initialised to {@code {"Camel", "camel"}}, with
 * {@code caseInsensitive} on). A replacement written from the call sites alone would have answered
 * every request with an internal status header. The outbound half is gone
 * (docs/vertx-native.md decision 1): a response is its own object that never contained the
 * request, so nothing has to be filtered out of it on the way to the wire.
 *
 * <p>The prefix is now the framework's own — {@code tql.} — and it is a dot rather than a hyphen
 * for a reason this rule has to state, because it is the rule that depends on it: the framework
 * sends hyphenated {@code Tesseraql-} headers <em>between its own nodes</em>
 * ({@code Tesseraql-Acting-Role}, minted by the stack relay and validated by the member), and
 * those must pass {@link #enters}. Widening the prefix to {@code Tesseraql} would disable acting
 * roles everywhere, and nothing about the header's name would say so.
 */
public final class HeaderFilter {

    /** The namespace every internal header name carries; see this class's note on the dot. */
    private static final String PREFIX = "tql.";

    private HeaderFilter() {
    }

    /** Whether a request header may be put onto the message. */
    public static boolean enters(String name) {
        return !internal(name);
    }

    /** The framework's own header names, which belong to the pipeline and not to the wire. */
    private static boolean internal(String name) {
        return name.regionMatches(true, 0, PREFIX, 0, PREFIX.length());
    }
}
