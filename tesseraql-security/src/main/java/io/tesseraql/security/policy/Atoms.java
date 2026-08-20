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

    /** The store-wide atom granting sight of the whole identity store. */
    public static final String IAM_ADMIN_VIEW = "tql.iam.admin.view";

    /** The store-wide atom granting writes across the whole identity store. */
    public static final String IAM_ADMIN_WRITE = "tql.iam.admin.write";

    /**
     * The atom prefix granting sight of one application's own access
     * ({@code tql.iam.view.<name>}, docs/access-governance.md structural decision 7).
     */
    public static final String IAM_VIEW_PREFIX = "tql.iam.view.";

    /**
     * The atom prefix delegating administration of one application's own access
     * ({@code tql.iam.write.<name>}, docs/access-governance.md structural decision 7).
     *
     * <p>It is strictly narrower than the store-wide {@link #IAM_ADMIN_WRITE}: a holder touches
     * only roles classified to an application they hold it for, grants only permission codes
     * carrying that application's name, and never reaches a stack-wide role or anything under
     * the {@code tql.} mark. Delegating administration of {@code orders} must not become a path
     * to granting {@code tql.app.deploy.*}.
     *
     * <p>Seeing and writing are two grants, not one — the {@code tql.ops.view.<name>} /
     * {@code tql.ops.run.<name>} pair, where neither implies the other, applied to identity.
     */
    public static final String IAM_WRITE_PREFIX = "tql.iam.write.";

    /**
     * The store-wide atom each per-application prefix narrows: a holder of the broader grant
     * satisfies the narrower check by construction.
     *
     * <p>Stated once, as data, because the alternative is restating it at every route that
     * checks a per-application atom — and a route that forgot half the pair would be a gate
     * only one of the two administrators could pass, which looks like nothing in review.
     * {@code SecurityConfig.policy} is where it is read: the synthesized atom policy ORs the
     * broader grant in beside the exact one and its terminal wildcard.
     */
    private static final java.util.Map<String, String> NARROWS = java.util.Map.of(
            IAM_VIEW_PREFIX, IAM_ADMIN_VIEW,
            IAM_WRITE_PREFIX, IAM_ADMIN_WRITE);

    private Atoms() {
    }

    /**
     * The store-wide atom {@code atomId} narrows, or {@code null} when it narrows none.
     *
     * <p>Only a per-application atom narrows anything: {@code tql.iam.write.orders} and the
     * whole-family {@code tql.iam.write.*} are both what {@code tql.iam.admin.write} already
     * covers, so a route naming either admits the store-wide administrator.
     */
    public static String narrowedFrom(String atomId) {
        if (atomId == null) {
            return null;
        }
        for (java.util.Map.Entry<String, String> entry : NARROWS.entrySet()) {
            if (atomId.startsWith(entry.getKey())
                    && atomId.length() > entry.getKey().length()) {
                return entry.getValue();
            }
        }
        return null;
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
