package io.tesseraql.cli;

import picocli.CommandLine.Option;

/**
 * The configuration option set (docs/cli-surface.md Decision 5): {@code --env}, mixed into every
 * command that loads a manifest. A flag that exists on {@code dev} and not on {@code lint} reads
 * as "lint does not have profiles" — the escape hatch ({@code TESSERAQL_ENV},
 * {@code -Dtesseraql.env}) always existed, which is why the gap was an inconsistency rather than
 * an outage. Membership stays a review question (Decision 7: capability cannot be read off the
 * command class); the option's name belongs to this mixin, guarded by shape.
 */
public final class ConfigOptions {

    @Option(names = {"--env"}, paramLabel = "<profile>", description = "Environment profile:"
            + " merges config/env/<profile>.yml between the base config and the Studio overlay"
            + " (also TESSERAQL_ENV).")
    public String env;

    /**
     * Applies the profile before any manifest loads — the same system property the environment
     * variable feeds, so every loader on the call path resolves the same profile. A no-op when
     * the flag was not given, leaving {@code TESSERAQL_ENV}/{@code -Dtesseraql.env} in charge.
     */
    public void apply() {
        if (env != null) {
            System.setProperty("tesseraql.env", env);
        }
    }
}
