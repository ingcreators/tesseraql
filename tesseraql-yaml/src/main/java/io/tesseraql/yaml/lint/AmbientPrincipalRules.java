package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ToolFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ambient {@code principal.*} SQL binds.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class AmbientPrincipalRules implements LintRule {

    private static final String PRINCIPAL_BIND_WITHOUT_AUTHENTICATION = "TQL-SEC-4136";

    private static final String REDUNDANT_PRINCIPAL_WIRING = "TQL-SEC-4137";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintAmbientPrincipal(context.appHome(), manifest, findings);
    }

    /**
     * Lints the ambient {@code principal.*} binds (docs/ambient-params.md): a bind on a route
     * that never carries an authenticated principal — {@code auth: public}, no effective
     * security at all, or a signature-authenticated webhook — can only fail at runtime as an
     * unbound parameter, so it is an error here ({@code TQL-SEC-4136}). A {@code params:} entry
     * that merely renames an ambient field is flagged toward the ambient spelling
     * ({@code TQL-SEC-4137}) — a migration nudge, not a rule.
     */
    void lintAmbientPrincipal(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            lintAmbientPrincipal(appHome, route.source(), route.definition(), false, findings);
        }
        // A queue consumer runs off a message, not a request: there is no caller to authenticate,
        // so a principal.* bind in its SQL can only ever fail at runtime.
        for (RouteFile consumer : manifest.consumers()) {
            lintAmbientPrincipal(appHome, consumer.source(), consumer.definition(), true,
                    findings);
        }
        // An MCP tool threads the caller's bearer token, so it carries a principal exactly when
        // its own security block says it does.
        for (ToolFile tool : manifest.tools()) {
            lintAmbientPrincipal(appHome, tool.source(), tool.definition(), false, findings);
        }
    }

    void lintAmbientPrincipal(Path appHome, Path file, RouteDefinition def,
            boolean neverAuthenticated, List<LintFinding> findings) {
        String source = appHome.relativize(file).toString().replace('\\', '/');
        boolean noPrincipal = neverAuthenticated
                || "webhook".equals(def.recipe())
                || def.security() == null
                || "public".equals(def.security().auth());
        if (noPrincipal) {
            for (String bind : principalBinds(file, def)) {
                findings.add(new LintFinding(PRINCIPAL_BIND_WITHOUT_AUTHENTICATION, ERROR, source,
                        "Route '" + def.id() + "' binds '" + bind + "' but never carries an"
                                + " authenticated principal — the bind can only fail as an"
                                + " unbound parameter at runtime"));
            }
        }
        sqlParamMaps(def).forEach((where, params) -> params.forEach((bindName, expr) -> {
            if (expr != null && io.tesseraql.core.sql.AmbientBinds.isAmbient(expr)
                    && expr.startsWith("principal.")) {
                findings.add(new LintFinding(REDUNDANT_PRINCIPAL_WIRING, WARNING, source,
                        "Route '" + def.id() + "' " + where + " wires '" + bindName + ": "
                                + expr + "' — the ambient bind /* " + expr + " */ makes the"
                                + " wiring unnecessary"));
            }
        }));
    }

    /**
     * Every {@code params:} map feeding a 2-way SQL <em>file</em>, labeled for the finding
     * message. Service invocations ({@code sql.service:}) are excluded: their params are the
     * service's arguments, not SQL binds, so the ambient namespace does not replace them — the
     * bundled Studio/account apps wire {@code principal.*} into services exactly this way, by
     * design.
     */
    private static Map<String, Map<String, String>> sqlParamMaps(RouteDefinition def) {
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        def.steps().forEach((name, step) -> {
            if (step.file() != null && step.params() != null) {
                maps.put("step '" + name + "'", step.params());
            }
        });
        def.sources().forEach((name, query) -> {
            if (query.file() != null && query.params() != null) {
                maps.put("query '" + name + "'", query.params());
            }
        });
        def.validate().forEach((name, rule) -> {
            if (rule.file() != null && rule.params() != null) {
                maps.put("validation rule '" + name + "'", rule.params());
            }
        });
        return maps;
    }

    /**
     * The distinct {@code principal.*} bind expressions across a document's parseable SQL files
     * — the principal half of the ambient set; the framework owns the list, not this linter.
     */
    private Set<String> principalBinds(Path source, RouteDefinition def) {
        return LintSupport.ambientBinds(context, source, def,
                expression -> expression.startsWith("principal.")
                        && io.tesseraql.core.sql.AmbientBinds.isAmbient(expression));
    }
}
