package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.InputPolicy;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * The servable-route family: recipe, security, response, and the fan-out into
 * every shared document rule a route carries.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class RouteRules implements LintRule {

    private static final String UNKNOWN_RECIPE = "TQL-YAML-1002";

    private static final String NEGATIVE_TIMEOUT = "TQL-YAML-1021";

    private static final String ENRICH_ON_COMMAND_STEP = "TQL-FIELD-2009";

    private static final String VALIDATE_WITHOUT_EFFECT = "TQL-YAML-1003";

    private static final String COMMAND_KEYS_WITHOUT_STEPS = "TQL-YAML-1052";

    private static final String INVALID_CSRF_MODE = "TQL-SEC-4132";

    private static final String INVALID_INPUT_POLICY = "TQL-FIELD-2006";

    private static final String POLICY_TEMPLATE_UNRESOLVABLE = "TQL-YAML-1409";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        Path appHome = context.appHome();
        for (RouteFile route : manifest.routes()) {
            lintRoute(appHome, manifest.config(), route, findings);
        }
    }

    static final Set<String> KNOWN_ROUTE_RECIPES = Set.of("query-json", "command-json",
            "query-html", "page", "query-export", "file-import", "file-export", "webhook");

    /** The {@code {name}} path parameters a URL template declares, in template order. */
    private static List<String> pathParams(String urlPath) {
        List<String> names = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = io.tesseraql.core.sql.SqlIdentifiers.PLACEHOLDER
                .matcher(urlPath == null ? "" : urlPath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * The route recipes whose compiled pipeline runs {@code validate:} — every recipe that can
     * reach the transactional command processor. {@code command-json} and {@code query-json}
     * both route into it once a validate block is present, and {@code webhook} delegates to it
     * unconditionally. Queue consumers and MCP tools run it too; they are linted separately
     * because they carry no route recipe.
     */
    private static final List<String> VALIDATING_RECIPES = List.of("command-json", "query-json",
            "webhook");

    void lintRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');
        UnknownKeyRules.lintUnknownKeys(context, appHome, route.source(), RouteDefinition.class,
                Set.of(), findings);

        if (!KNOWN_ROUTE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding(UNKNOWN_RECIPE, ERROR, source,
                    "Unknown route recipe '" + definition.recipe() + "'",
                    context.lineOf(route.source(), "recipe:"), null));
        }
        // A negative timeout on a step or named source was clamped to 0 = unlimited by the
        // compiler — the inverse of the author's intent — so the guard was missing here.
        definition.steps().forEach((name, step) -> {
            if (step.timeoutSeconds() != null && step.timeoutSeconds() < 0) {
                findings.add(new LintFinding(NEGATIVE_TIMEOUT, ERROR, source,
                        "Step '" + name + "' timeoutSeconds must be >= 0"));
            }
            // A route step is a write — the transactional arms only (decision 9) — so it
            // publishes affectedRows and keys, never rows. An enrichment folds into rows, so
            // there is nothing here for one to fold into, and the compiler builds enrichment
            // processors from sources: alone. Declared here it was accepted and dropped.
            if (!step.enrich().isEmpty()) {
                findings.add(new LintFinding(ENRICH_ON_COMMAND_STEP, ERROR, source, "Step '" + name
                        + "' declares enrich: but a command step writes - it publishes"
                        + " affectedRows and keys, not rows. An enrichment nests under the"
                        + " source whose rows it folds into."));
            }
        });
        definition.sources().forEach((name, query) -> {
            if (query.timeoutSeconds() != null && query.timeoutSeconds() < 0) {
                findings.add(new LintFinding(NEGATIVE_TIMEOUT, ERROR, source,
                        "Source '" + name + "' timeoutSeconds must be >= 0"
                                + " (0 disables the statement timeout)",
                        context.lineOf(route.source(), "timeoutSeconds:"), null));
            }
        });
        if (definition.main() != null && !definition.main().isContract()
                && definition.main().file() != null) {
            Path sqlFile = route.source().getParent().resolve(definition.main().file());
            if (!Files.isRegularFile(sqlFile)) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Referenced SQL file is missing: " + definition.main().file()));
            }
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(route.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        definition.sources().forEach((name, query) -> {
            if (query.file() != null
                    && !Files.isRegularFile(route.source().getParent().resolve(query.file()))) {
                findings.add(new LintFinding(LintCodes.MISSING_SQL_FILE, ERROR, source,
                        "Query '" + name + "' references a missing SQL file: " + query.file()));
            }
        });
        DocumentRules.lintOptimisticLocking(context, route.source(), definition, source, findings);
        // Whether the recipe honors validate: at all is a route-level question; the rules'
        // shape is checked the same way wherever they are declared.
        if (!definition.validate().isEmpty() && definition.recipe() != null
                && !VALIDATING_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding(VALIDATE_WITHOUT_EFFECT, ERROR, source,
                    "validate: has no effect on '" + definition.recipe() + "' routes — it is"
                            + " honored on " + String.join(", ", VALIDATING_RECIPES)
                            + ", queue consumers, and MCP tools"));
        }
        DocumentRules.lintValidation(context, route.source(), definition, source, findings);
        LiveViewRules.lintEmit(definition, source, findings);
        DocumentRules.lintInvalidates(context, definition, source, findings);
        HttpSourceRules.lintHttpSources(config, definition, source, findings);
        EnrichRules.lintEnrich(context, config, route.source(), definition, source, findings);
        RateLimitRules.lintRateLimitScope(definition, source, findings);
        HttpCacheRules.lintHttpCache(definition, source, findings);
        MessagingRules.lintNotify(config, definition, source, findings, context.functions());
        MessagingRules.lintWebhook(config, definition, source, findings);
        MessagingRules.lintPublish(config, definition, source, findings);
        // Everything that rides the command transaction needs the command: publish:, notify:,
        // outbox: and validate: make a command-json route transactional, and the command
        // processor refuses an empty steps: at build — say it at authoring time instead.
        if ("command-json".equals(definition.recipe()) && definition.steps().isEmpty()
                && (definition.publish() != null || !definition.notifications().isEmpty()
                        || definition.outbox() != null || !definition.validate().isEmpty())) {
            findings.add(new LintFinding(COMMAND_KEYS_WITHOUT_STEPS, ERROR, source, "route '"
                    + definition.id() + "' declares publish:/notify:/outbox:/validate: but no"
                    + " steps: pipeline — these ride the command transaction"));
        }
        if (definition.consume() != null) {
            findings.add(new LintFinding(LintCodes.MESSAGING_KEY_ON_WRONG_RECIPE, ERROR, source,
                    "consume: is only"
                            + " supported on a queue-consume route under consume/, not the '"
                            + definition.recipe() + "' recipe"));
        }
        ExportRules.lintRouteExport(context, route, definition, source, findings);
        ExportRules.lintExportRowCap(definition.fileExport(), "", source, findings);
        ExportRules.lintExportSources(context, definition.fileExport(), definition.sources(),
                ExportRules.extractionSqlFile(route, definition), "", source, findings);
        DocumentRules.lintDatasource(context, config, route.source(), definition, source, findings);
        DocumentRules.lintEmbeddedVariables(context, route.source(), definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null) {
            // A policy that resolves an atom from the route's own path must resolve on this
            // route (docs/access-governance.md structural decision 7). The compiler refuses the
            // same thing at boot; here it is an error with a line number rather than a stack.
            String templateViolation = io.tesseraql.yaml.app.PolicyCodes.templateViolation(
                    definition.security().policy(), pathParams(route.urlPath()));
            if (templateViolation != null) {
                findings.add(new LintFinding(POLICY_TEMPLATE_UNRESOLVABLE, ERROR, source,
                        templateViolation, context.lineOf(route.source(), "policy:"), null));
            } else if (!DocumentRules.policyDefined(config, definition.security().policy())) {
                findings.add(new LintFinding(LintCodes.UNDEFINED_POLICY, WARNING, source,
                        "Route references undefined policy '" + definition.security().policy()
                                + "' (deny by default)"));
            }
        }
        if (definition.security() != null) {
            String csrf = definition.security().csrf();
            // The route-local csrf value gets the same enum check the config-side
            // security.defaults rules already enforce (TQL-SEC-4132) — a typo like `requred`
            // silently resolved to auto (no enforcement on a bearer route) before this.
            if (csrf != null && !"auto".equals(csrf) && !"required".equals(csrf)
                    && !"off".equals(csrf)) {
                findings.add(new LintFinding(INVALID_CSRF_MODE, ERROR, source,
                        "Route '" + definition.id() + "' csrf must be auto, required or off, not '"
                                + csrf + "'"));
            }
        }
        lintInputPolicy(definition, source, findings);
        DocumentRules.lintTenantPredicate(context, config, route.source(), definition, source,
                findings);
    }

    /**
     * Validates the {@code inputPolicy:} value vocabulary (TQL-FIELD-2006). A value outside the
     * enum silently disabled the guard: {@code unknownFields: Reject} (or {@code rejct}, {@code
     * deny}) made {@code rejectsUnknownFields()} false and admitted every undeclared body field.
     * The schema types {@code inputPolicy} openly, so this is the only value check.
     */
    private void lintInputPolicy(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        InputPolicy policy = definition.inputPolicy();
        if (policy == null) {
            return;
        }
        String unknown = policy.unknownFields();
        if (unknown != null && !"reject".equals(unknown) && !"ignore".equals(unknown)) {
            findings.add(new LintFinding(INVALID_INPUT_POLICY, ERROR, source,
                    "Route '" + definition.id() + "' inputPolicy.unknownFields must be reject or "
                            + "ignore, not '" + unknown + "' (an unrecognized value silently "
                            + "disables the mass-assignment guard)"));
        }
        String readOnly = policy.readOnlyFieldBehavior();
        if (readOnly != null && !"reject".equals(readOnly) && !"ignore".equals(readOnly)
                && !"warn".equals(readOnly)) {
            findings.add(new LintFinding(INVALID_INPUT_POLICY, ERROR, source,
                    "Route '" + definition.id() + "' inputPolicy.readOnlyFieldBehavior must be "
                            + "reject, ignore or warn, not '" + readOnly + "'"));
        }
    }
}
