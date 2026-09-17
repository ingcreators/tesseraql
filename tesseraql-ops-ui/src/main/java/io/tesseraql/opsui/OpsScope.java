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

    /** The atom prefix granting deployment of one application. */
    public static final String DEPLOY_PREFIX = "tql.app.deploy.";

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
     * The root-span filter for the trace pages (docs/audit-low-leads.md decision 4): an
     * attributed root passes exactly as {@link #view} says, and an <em>unattributed</em> root —
     * {@code null}, framework-internal work such as an outbound call, or a trace whose
     * attributed root the ring has evicted — passes only for a caller holding the wildcard
     * {@code tql.ops.view.*}.
     *
     * <p>Its own predicate on purpose. {@link #view} is one predicate for five tables, and its
     * {@code app != null} is the fence the shared-database work put in front of the grant; a
     * runtime's own in-memory spans are exactly what its console should show
     * (docs/app-isolation-model.md decision 4), and the wildcard reader was promised the
     * unattributed ones from the start. Admitting null here restores that promise without
     * touching the table scope.
     */
    public static Predicate<String> traces(Object permissions, Set<String> servedApps) {
        Predicate<String> attributed = view(permissions, servedApps);
        boolean wildcard = holdsWildcard(VIEW_PREFIX, permissions);
        return app -> app == null ? wildcard : attributed.test(app);
    }

    /**
     * The app-name filter for a caller's <em>deploy</em> authority — the deploy page's member
     * table (docs/stack-shells.md, the deploy page). Reach only: the endpoint re-checks the
     * atom against the package's declared name on every submit.
     */
    public static Predicate<String> deploy(Object permissions, Set<String> servedApps) {
        return compose(granted(DEPLOY_PREFIX, permissions), servedApps);
    }

    /**
     * Whether the caller holds any {@code tql.app.deploy} grant at all — the deploy page's
     * display gate: the shell's nav entry renders and the page answers only for a holder,
     * deny by default like the switcher.
     */
    public static boolean holdsAnyDeploy(Object permissions) {
        if (!(permissions instanceof List<?> codes)) {
            return false;
        }
        return codes.stream().map(String::valueOf)
                .anyMatch(code -> code.startsWith(DEPLOY_PREFIX));
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

    /** Whether the caller holds the terminal {@code *} under {@code prefix}. */
    private static boolean holdsWildcard(String prefix, Object permissions) {
        return permissions instanceof List<?> codes
                && codes.stream().map(String::valueOf).anyMatch((prefix + "*")::equals);
    }
}
