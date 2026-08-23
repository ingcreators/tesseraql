package io.tesseraql.core.json;

/**
 * The stream-read bounds every JSON and YAML parse in the framework declares
 * (docs/security-hardening.md; docs/duplication-consolidation.md, campaign 4): limits the code
 * pins rather than whatever the resolved Jackson defaults happen to be, so a dependency bump
 * cannot silently widen what a request body may do to the parser.
 *
 * <p>Plain constants rather than a mapper factory, because this module carries no Jackson: the
 * factories live where Jackson does — {@code io.tesseraql.yaml.JsonMappers} for every module
 * above it, and the local factories in the modules below it (security, mcp), which read these
 * numbers so the bounds cannot drift apart.
 */
public final class JsonLimits {

    /**
     * The most nesting a document may carry. Real payloads nest a handful of levels; this is
     * generous headroom and far below the depth at which deserialization recursion threatens
     * the stack.
     */
    public static final int MAX_NESTING_DEPTH = 100;

    /** Jackson's documented default, pinned so a dependency change cannot move it. */
    public static final int MAX_STRING_LENGTH = 20_000_000;

    /** Jackson's documented default, pinned so a dependency change cannot move it. */
    public static final int MAX_NAME_LENGTH = 65_536;

    private JsonLimits() {
    }
}
