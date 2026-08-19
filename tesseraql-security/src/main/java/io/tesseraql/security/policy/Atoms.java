package io.tesseraql.security.policy;

import java.util.List;

/**
 * The framework's grant atoms — {@code tql.<family>.<verb>.<name|*>} over dot-free application
 * names, plus the store-wide exact strings (docs/stack-shells.md structural decision 1). Atoms
 * are permission codes in the shared identity store, granted to principals like any other code;
 * the {@code tql.} mark keeps them disjoint from every application's own vocabulary, exactly as
 * {@code /_tesseraql/} marks the framework's URL space.
 */
public final class Atoms {

    /** The framework's mark: every atom lives under it, and no application code may. */
    public static final String MARK = "tql.";

    /** The atom prefix granting use of one application ({@code tql.app.use.<name>}). */
    public static final String APP_USE_PREFIX = "tql.app.use.";

    /** The atom prefix granting deployment of one application ({@code tql.app.deploy.<name>}). */
    public static final String APP_DEPLOY_PREFIX = "tql.app.deploy.";

    /** The atom prefix granting sight of one application's operational data ({@code tql.ops.view.<name>}). */
    public static final String OPS_VIEW_PREFIX = "tql.ops.view.";

    /** The atom prefix granting action on one application's operations ({@code tql.ops.run.<name>}). */
    public static final String OPS_RUN_PREFIX = "tql.ops.run.";

    /** The atom prefix reserved for Studio editing of one application ({@code tql.studio.edit.<name>}). */
    public static final String STUDIO_EDIT_PREFIX = "tql.studio.edit.";

    private Atoms() {
    }

    /**
     * Whether the granted permission codes carry {@code tql.app.use.<appName>} — the business
     * user's reach into one application, honoured exactly or as the terminal wildcard
     * {@code tql.app.use.*} (an exact string, not a glob).
     */
    public static boolean appUse(List<String> permissions, String appName) {
        return holds(permissions, APP_USE_PREFIX, appName);
    }

    /** Whether {@code permissions} holds {@code prefix + name} or the terminal wildcard. */
    public static boolean holds(List<String> permissions, String prefix, String name) {
        if (permissions == null) {
            return false;
        }
        return permissions.contains(prefix + name) || permissions.contains(prefix + "*");
    }
}
