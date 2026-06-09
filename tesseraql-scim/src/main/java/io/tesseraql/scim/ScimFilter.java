package io.tesseraql.scim;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal SCIM filter (design ch. 10.15, RFC 7644 §3.4.2.2): the {@code <attribute> eq "<value>"}
 * form used by provisioning clients to look up a resource before create. Other operators are
 * rejected with {@code invalidFilter} until broader filtering is supported.
 *
 * @param attribute the attribute being compared (e.g. {@code userName})
 * @param value     the literal value
 */
public record ScimFilter(String attribute, String value) {

    private static final Pattern EQ = Pattern.compile("^\\s*(\\w+)\\s+eq\\s+\"(.*)\"\\s*$");

    /** Parses an {@code eq} filter, or throws {@code TQL-SCIM 400 invalidFilter}. */
    public static ScimFilter parse(String filter) {
        Matcher matcher = EQ.matcher(filter == null ? "" : filter);
        if (!matcher.matches()) {
            throw new ScimException(400, "invalidFilter", "Unsupported filter: " + filter);
        }
        return new ScimFilter(matcher.group(1), matcher.group(2));
    }
}
