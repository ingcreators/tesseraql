package io.tesseraql.yaml.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The Camel component policy (docs/component-guard.md): which components may exist on the
 * runtime's CamelContext. Application YAML never carries a raw endpoint URI — recipes construct
 * every endpoint — so this guards the paths that bypass the YAML surface: classpath drift (a
 * dependency upgrade putting {@code camel-exec} on the classpath), plugin JARs registering
 * components directly, and framework regressions.
 *
 * <p>Read from {@code tesseraql.camel.components}:
 *
 * <pre>{@code
 * camel:
 *   components:
 *     allowed: [smtp]        # optional: NARROWS beyond the framework floor
 *     denied: [ftp]          # adds to the built-in baseline
 * }</pre>
 *
 * <p>Three invariants:
 * <ul>
 * <li><b>The baseline holds without config.</b> {@link #BASELINE_DENIED} is refused in every
 * app; a missing config block is the secure posture, not an open one.</li>
 * <li><b>Config narrows, never widens.</b> {@code denied:} adds to the baseline; an
 * {@code allowed:} list restricts further. A config entry re-allowing a baseline-denied
 * component is ignored (and linted, {@code TQL-SEC-4139}).</li>
 * <li><b>The framework floor always resolves.</b> {@link #FRAMEWORK_FLOOR} plus every
 * {@code tesseraql-*} component is implicitly allowed, so an {@code allowed:} list only ever
 * governs what the application adds beyond the framework.</li>
 * </ul>
 */
public final class ComponentPolicy {

    /**
     * Refused in every application, config or not: process execution, script evaluation, and
     * reflective invocation-by-name — components whose presence turns any injection primitive
     * into code execution.
     */
    public static final Set<String> BASELINE_DENIED = Set.of(
            "exec", "script", "groovy", "class", "language", "bean");

    /**
     * The components the framework itself registers unconditionally.
     *
     * <p>It used to hold the recipes' endpoints and the runtime's transports; the recipes stopped
     * having endpoints (docs/camel-removal.md decisions 1 and 5), and a remote file transport is
     * admitted by the job that declares it rather than by standing here — which is the narrower
     * answer, because an app that declares no SFTP source should not have SFTP resolvable.
     * Kept honest by the runtime integration suites, which boot the gallery and bundled apps under
     * the guard, plus the explicit registered-components assertion in
     * {@code StudioIntegrationTest}.
     */
    public static final Set<String> FRAMEWORK_FLOOR = Set.of("properties");

    /** Whether the name is framework-registered: the floor set, or any {@code tesseraql-*}
     * component — the framework's own component namespace, present and future. */
    static boolean frameworkComponent(String name) {
        return FRAMEWORK_FLOOR.contains(name) || name.startsWith("tesseraql-");
    }

    private final Set<String> allowed;
    private final Set<String> denied;
    private final boolean allowedDeclared;

    private ComponentPolicy(Set<String> allowed, Set<String> denied, boolean allowedDeclared) {
        this.allowed = Set.copyOf(allowed);
        this.denied = Set.copyOf(denied);
        this.allowedDeclared = allowedDeclared;
    }

    /** Parses {@code tesseraql.camel.components}; an absent block still enforces the baseline. */
    public static ComponentPolicy from(AppConfig config) {
        Set<String> allowed = names(config, "tesseraql.camel.components.allowed");
        Set<String> denied = names(config, "tesseraql.camel.components.denied");
        boolean allowedDeclared = config.navigate("tesseraql.camel.components.allowed") != null;
        return new ComponentPolicy(allowed, denied, allowedDeclared);
    }

    private static Set<String> names(AppConfig config, String path) {
        Set<String> names = new LinkedHashSet<>();
        if (config.navigate(path) instanceof List<?> list) {
            for (Object entry : list) {
                names.add(config.resolve(String.valueOf(entry)).trim()
                        .toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /**
     * Why the named component must not exist on the context, or empty when it may. The reason
     * text is user-facing (it ends up in the boot failure and the lint message).
     */
    public Optional<String> refusal(String componentName) {
        String name = componentName.toLowerCase(Locale.ROOT);
        if (BASELINE_DENIED.contains(name)) {
            return Optional.of("refused by the built-in baseline (docs/component-guard.md);"
                    + " config cannot re-allow it");
        }
        if (denied.contains(name)) {
            return Optional.of("denied by tesseraql.camel.components.denied");
        }
        if (allowedDeclared && !allowed.contains(name) && !frameworkComponent(name)) {
            return Optional.of("not in tesseraql.camel.components.allowed (the framework's own"
                    + " components are always permitted)");
        }
        return Optional.empty();
    }

    /** Whether the app declares an {@code allowed:} list (the narrowing mode). */
    public boolean allowedDeclared() {
        return allowedDeclared;
    }

    /** The declared allow list, lowercased. */
    public Set<String> allowed() {
        return allowed;
    }

    /** The declared deny list, lowercased (the baseline is separate and implicit). */
    public Set<String> denied() {
        return denied;
    }
}
