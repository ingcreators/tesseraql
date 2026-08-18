package io.tesseraql.opsui;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Application scope for the operations surface: two filters composed, both of which must pass.
 *
 * <p><b>What this runtime serves.</b> The ops tables live in a business database that several
 * runtimes may share, so a grant alone was letting one runtime's surface list another runtime's
 * jobs, executions and transfers — rows it has no other relationship with. One runtime serves one
 * application, and this narrows to exactly that; on the stack shell the same composition runs
 * with the member list as the served set.
 *
 * <p><b>What the caller was granted.</b> The framework's atoms are
 * {@code tql.<family>.<verb>.<name|*>} (docs/stack-shells.md structural decision 1). Two verbs
 * carry the operations family: {@code tql.ops.view.<name>} grants operational visibility into one
 * application, {@code tql.ops.run.<name>} the authority to act on it — run, cancel and rerun
 * jobs, redeliver outbox and dead-lettered events. The wildcard is a terminal {@code *}
 * ({@code tql.ops.view.*}), an exact string rather than a glob. View and act are different
 * authorities to grant — <em>view broadly, act narrowly</em> is the asymmetry the retired
 * two-axis model ({@code ops.batch.view}/{@code ops.batch.run} entry permissions plus one
 * {@code ops.app.<name>} set scoping both verbs) could not express. Deny by default: a caller
 * without any {@code tql.ops.view} grant sees nothing.
 */
public final class OpsScope {

    /** The atom prefix granting per-application operational visibility. */
    public static final String VIEW_PREFIX = "tql.ops.view.";

    /** The atom prefix granting per-application operational actions. */
    public static final String RUN_PREFIX = "tql.ops.run.";

    private OpsScope() {
    }

    /**
     * The app-name filter for a caller's <em>view</em> verb: the applications {@code servedApps}
     * contains, narrowed to those the caller's {@code tql.ops.view} grants cover.
     *
     * @param permissions the {@code principal.permissions} value a route binds into the service
     *                    call — a list of permission codes; any other shape denies
     * @param servedApps  the applications in reach — what this runtime serves, or on the stack
     *                    shell the member list; an empty set denies
     */
    public static Predicate<String> view(Object permissions, Set<String> servedApps) {
        return compose(granted(VIEW_PREFIX, permissions), servedApps);
    }

    /** The app-name filter for a caller's <em>run</em> verb — acting, not seeing. */
    public static Predicate<String> run(Object permissions, Set<String> servedApps) {
        return compose(granted(RUN_PREFIX, permissions), servedApps);
    }

    /**
     * Whether the caller holds any {@code tql.ops.view} grant at all — the gate for the
     * stack-wide vitals (JVM pinning, lanes, slow SQL, the gateway's health), which describe the
     * shared substrate the caller's application runs on and belong to no single member
     * (docs/stack-shells.md structural decision 1).
     */
    public static boolean holdsAnyView(Object permissions) {
        if (!(permissions instanceof List<?> codes)) {
            return false;
        }
        return codes.stream().map(String::valueOf)
                .anyMatch(code -> code.startsWith(VIEW_PREFIX));
    }

    private static Predicate<String> compose(Predicate<String> granted, Set<String> servedApps) {
        Set<String> served = servedApps == null ? Set.of() : Set.copyOf(servedApps);
        return app -> app != null && served.contains(app) && granted.test(app);
    }

    /** The grant half on its own: the codes under {@code prefix}, terminal {@code *} honoured. */
    private static Predicate<String> granted(String prefix, Object permissions) {
        if (!(permissions instanceof List<?> codes)) {
            return app -> false;
        }
        Set<String> scoped = codes.stream()
                .map(String::valueOf)
                .filter(code -> code.startsWith(prefix))
                .collect(Collectors.toSet());
        if (scoped.contains(prefix + "*")) {
            return app -> true;
        }
        return app -> scoped.contains(prefix + app);
    }
}
