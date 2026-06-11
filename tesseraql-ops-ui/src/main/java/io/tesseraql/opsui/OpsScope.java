package io.tesseraql.opsui;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Per-user application scope for the operations console (design ch. 26.11): permissions of the
 * form {@code ops.app.<appName>} grant operational visibility into one app, {@code ops.app.*}
 * into all. Deny by default (design ch. 11): a caller without any {@code ops.app.} grant sees no
 * batch data - the {@code ops.batch.view} entry permission opens the console, the scoped grants
 * decide what it shows.
 */
public final class OpsScope {

    /** The permission prefix granting per-app operational visibility. */
    public static final String PERMISSION_PREFIX = "ops.app.";
    private static final String ALL = PERMISSION_PREFIX + "*";

    private OpsScope() {
    }

    /**
     * The app-name filter for a caller, from the {@code principal.permissions} value a route
     * binds into the service call (a list of permission codes; any other shape denies).
     */
    public static Predicate<String> allowedApps(Object permissions) {
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
        return app -> app != null && scoped.contains(PERMISSION_PREFIX + app);
    }
}
