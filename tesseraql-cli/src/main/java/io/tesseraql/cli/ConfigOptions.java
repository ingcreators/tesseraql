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

    @Option(names = {"--repo"}, paramLabel = "<dir>", description = "Local artifact repository to"
            + " resolve modules from — a bag produced by 'tesseraql modules fetch' on a connected"
            + " machine (also -Dmaven.repo.local). Combine with --offline to resolve nothing over"
            + " the network.")
    public java.nio.file.Path repo;

    /**
     * Applies the profile and the repository location before any manifest loads or any module
     * resolves — the same system properties the environment feeds, so every loader and resolver
     * on the call path sees the same answer. Each is a no-op when its flag was not given, leaving
     * {@code TESSERAQL_ENV}/{@code -Dtesseraql.env} and {@code -Dmaven.repo.local} in charge.
     *
     * <p>{@code --repo} is a system property rather than a resolver argument because that is what
     * the embedded resolver already reads (ShrinkWrap's settings builder honors
     * {@code maven.repo.local}, as Maven itself does), so one flag relocates every resolution a
     * command performs without a second code path.
     */
    public void apply() {
        if (env != null) {
            System.setProperty("tesseraql.env", env);
        }
        if (repo != null) {
            System.setProperty("maven.repo.local", repo.toAbsolutePath().toString());
        }
    }
}
