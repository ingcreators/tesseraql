package io.tesseraql.opsui;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Application scope for the operations console: two filters composed, both of which must pass.
 *
 * <p><b>What this runtime serves.</b> The console shows the applications its own runtime serves
 * and nothing else (docs/app-isolation-model.md decision 4). The ops tables live in a business
 * database that several runtimes may share, so a grant alone was letting a console list another
 * runtime's jobs, executions and transfers — rows it has no other relationship with. Under
 * isolated hosting each runtime serves one app and this narrows to exactly that app.
 *
 * <p><b>What the caller was granted.</b> Permissions of the form {@code ops.app.<appName>} grant
 * operational visibility into one app, {@code ops.app.*} into all. Deny by default (design
 * ch. 11): a caller without any {@code ops.app.} grant sees no batch data — the
 * {@code ops.batch.view} entry permission opens the console, the scoped grants decide what it
 * shows.
 */
public final class OpsScope {

    /** The permission prefix granting per-app operational visibility. */
    public static final String PERMISSION_PREFIX = "ops.app.";
    private static final String ALL = PERMISSION_PREFIX + "*";

    private OpsScope() {
    }

    /**
     * The app-name filter for a caller: the applications {@code servedApps} contains, narrowed to
     * those the caller's grants cover.
     *
     * @param permissions the {@code principal.permissions} value a route binds into the service
     *                    call — a list of permission codes; any other shape denies
     * @param servedApps  the applications this runtime serves; an empty set denies, because a
     *                    runtime serving nothing has nothing to show
     */
    public static Predicate<String> allowedApps(Object permissions, Set<String> servedApps) {
        Predicate<String> granted = granted(permissions);
        Set<String> served = servedApps == null ? Set.of() : Set.copyOf(servedApps);
        return app -> app != null && served.contains(app) && granted.test(app);
    }

    /** The grant half on its own, for callers that have already narrowed to what they serve. */
    private static Predicate<String> granted(Object permissions) {
        if (!(permissions instanceof List<?> codes)) {
            return app -> false;
        }
        Set<String> scoped = codes.stream()
                .map(String::valueOf)
                .filter(code -> code.startsWith(PERMISSION_PREFIX))
                .collect(Collectors.toSet());
        if (scoped.contains(ALL)) {
            return app -> true;
        }
        return app -> scoped.contains(PERMISSION_PREFIX + app);
    }
}
