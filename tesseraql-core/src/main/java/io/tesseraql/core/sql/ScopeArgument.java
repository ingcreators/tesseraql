package io.tesseraql.core.sql;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The argument of a {@code /*%scope … *}{@code /} directive: the scope name, an optional table
 * alias after {@code on}, and whether {@code as boolean} asked for a SELECT-list flag rather than
 * a WHERE predicate.
 *
 * <p>Three modules read this argument — the parser that renders the directive, the linter that
 * checks the scope is declared and that a write on a scoped table is governed, and the coverage
 * manifest that attributes a suite run to a scope — and each had grown its own
 * {@code indexOf(" on ")} and {@code endsWith(" as boolean")}. A directive written across two
 * lines therefore meant three different things: the parser lost the alias, the linter reported the
 * whole argument as an undeclared scope, and coverage recorded the mangled name. Sub-keywords are
 * separated by whitespace, not by one space, and there is one place that knows it
 * (docs/two-way-sql-parser.md decision 7).
 */
public record ScopeArgument(String name, String alias, boolean asBoolean) {

    private static final Pattern AS_BOOLEAN = Pattern.compile("\\s+as\\s+boolean\\s*$");

    /**
     * The first {@code on} separates the name from the alias. First rather than last because a
     * scope name is an identifier and cannot carry whitespace; an alias that itself reads
     * {@code on} is refused downstream as a non-identifier.
     */
    private static final Pattern ON = Pattern.compile("\\s+on\\s+");

    /** Splits a directive argument into its parts; whitespace of any kind separates them. */
    public static ScopeArgument parse(String argument) {
        String rest = argument.trim();
        Matcher flag = AS_BOOLEAN.matcher(rest);
        boolean asBoolean = flag.find();
        if (asBoolean) {
            rest = rest.substring(0, flag.start()).trim();
        }
        Matcher on = ON.matcher(rest);
        if (on.find()) {
            return new ScopeArgument(rest.substring(0, on.start()).trim(),
                    rest.substring(on.end()).trim(), asBoolean);
        }
        return new ScopeArgument(rest, null, asBoolean);
    }
}
