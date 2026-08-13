package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Path-matched {@code security.defaults.routes} and what a route
 * inherits from them (docs/shared-definitions.md).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class SecurityDefaultRules implements LintRule {

    private static final String REPLACED_SECURITY_DEFAULTS = "TQL-SEC-4130";

    private static final String PUBLIC_ROUTE_UNDER_DEFAULT_POLICY = "TQL-SEC-4131";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintSecurityDefaults(context.appHome(), manifest, manifest.config(), findings);
    }

    /**
     * Lints the path-matched route security defaults (docs/route-defaults.md): the retired
     * kind-keyed {@code defaults.api}/{@code defaults.htmx} shape never had a consumer and is
     * flagged toward {@code defaults.routes}, and a route left {@code public} under a rule that
     * declares a policy is either deliberate (declare the route's own security) or the exact
     * mistake the default exists to catch.
     */
    void lintSecurityDefaults(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        Object legacy = config.navigate("tesseraql.security.defaults");
        if (legacy instanceof Map<?, ?> map && (map.containsKey("api")
                || map.containsKey("htmx"))) {
            findings.add(new LintFinding(REPLACED_SECURITY_DEFAULTS, WARNING, "config",
                    "tesseraql.security.defaults.api/htmx is replaced by the path-matched"
                            + " security.defaults.routes rules and has no effect"));
        }
        // A malformed rule list already failed the manifest load (TQL-SEC-4132) before lint ran.
        io.tesseraql.yaml.config.SecurityDefaults defaults = io.tesseraql.yaml.config.SecurityDefaults
                .from(config);
        if (defaults.isEmpty()) {
            return;
        }
        for (RouteFile route : manifest.routes()) {
            var security = route.definition().security();
            if (security == null || !"public".equals(security.auth())) {
                continue;
            }
            defaults.matchedRule(route.urlPath()).ifPresent(rule -> {
                if (rule.policy() != null) {
                    findings.add(new LintFinding(PUBLIC_ROUTE_UNDER_DEFAULT_POLICY, WARNING,
                            appHome.relativize(route.source()).toString(),
                            "Route '" + route.definition().id() + "' is public, but the security"
                                    + " default rule '" + rule.match() + "' declares policy '"
                                    + rule.policy() + "' for its path — confirm the route is"
                                    + " deliberately open"));
                }
            });
        }
    }
}
