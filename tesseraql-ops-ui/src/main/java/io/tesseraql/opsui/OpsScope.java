package io.tesseraql.opsui;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Per-user application scope for the operations console (design ch. 26.11): permissions of the
 * form {@code ops.app.<appName>} grant operational visibility into one app, {@code ops.app.*}
 * into all. Scoping activates as soon as the caller holds any {@code ops.app.} permission; a
 * caller with none keeps the legacy runtime-wide view their entry permission
 * ({@code ops.batch.view}) always granted, so existing deployments keep working until they adopt
 * scoped grants.
 */
public final class OpsScope {

    /** The permission prefix granting per-app operational visibility. */
    public static final String PERMISSION_PREFIX = "ops.app.";
    private static final String ALL = PERMISSION_PREFIX + "*";

    private OpsScope() {
    }

    /**
     * The app-name filter for a caller, from the {@code principal.permissions} value a route
     * binds into the service call (a list of permission codes; any other shape means no scoped
     * grants).
     */
    public static Predicate<String> allowedApps(Object permissions) {
        if (!(permissions instanceof List<?> codes)) {
            return app -> true;
        }
        Set<String> scoped = codes.stream()
                .map(String::valueOf)
                .filter(code -> code.startsWith(PERMISSION_PREFIX))
                .collect(Collectors.toSet());
        if (scoped.isEmpty()) {
            return app -> true;
        }
        if (scoped.contains(ALL)) {
            return app -> true;
        }
        return app -> app != null && scoped.contains(PERMISSION_PREFIX + app);
    }
}
