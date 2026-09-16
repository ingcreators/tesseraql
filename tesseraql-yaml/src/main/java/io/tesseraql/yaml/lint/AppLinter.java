package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Statically lints an app home, independent of Maven, so it is unit-testable (design ch. 18, 20).
 *
 * <p>The rules themselves live one family to a class (docs/lint-restructure.md decision 1),
 * along the fault line the test suite already had — {@code AppLinterDecisionsTest},
 * {@code AppLinterMessagingTest}, … This class is what remains: it loads the app, builds the
 * {@link LintContext} every family reads through, and runs {@link #rules()} in order.
 *
 * <p>{@link #rules()} is the report. Its order is the emission order a reader sees and a handful
 * of tests assert, so it is written out explicitly rather than discovered — inserting a family
 * is a decision someone reviews, not an accident of where a method landed in the file.
 */
public final class AppLinter {

    /**
     * The rule families, in the order they report (docs/lint-restructure.md decision 6, pinned
     * by {@code LintRegistryOrderTest}). Documents come first, family by family, then the
     * whole-app checks that need every document loaded, and finally the sweep for files no
     * loader claims at all.
     *
     * <p>Built per run rather than held as a constant: a family holds its run's
     * {@link LintContext}, so sharing one list across concurrent lints — two Studio requests,
     * a build linting several apps — would let one run's context leak into another's rules.
     */
    static List<LintRule> rules() {
        return List.of(
                new ApplicationNameRules(),
                new OAuthScopeRules(),
                new PolicyCodeRules(),
                new DeclaredRoleRules(),
                new RouteRules(),
                new CalendarRules(),
                new JobRules(),
                new JobChainingRules(),
                new ToolRules(),
                new ResourceRules(),
                new UiResourceRules(),
                new PromptRules(),
                new ConsumerRules(),
                new DuplicateMcpNameRules(),
                new McpReadFloorRules(),
                new ToolUiLinkRules(),
                new I18nRules(),
                new JwtConfigRules(),
                new ApiKeyConfigRules(),
                new BearerConfigRules(),
                new AuthWithoutPolicyRules(),
                new BatchHeartbeatRules(),
                new MtlsConfigRules(),
                new OidcSamlRules(),
                new SecurityDefaultRules(),
                new FieldDomainRules(),
                new ResponseHeaderRules(),
                new AmbientPrincipalRules(),
                new RuleSetRules(),
                new DecisionRules(),
                new ScopeRules(),
                new PreferenceRules(),
                new OrgUnitRules(),
                new TenancyConfigRules(),
                new WorkflowRules(),
                new AttachmentRules(),
                new MailRules(),
                new CatalogLocaleRules(),
                new FilesConfigRules(),
                new ObjectStorageEgressRules(),
                new ViewRules(),
                new BasePathRules(),
                new DuckDbRules(),
                new ModuleDeclarationRules(),
                new InputRules(),
                new DeclaredKindRules(),
                new UnclaimedFileRules());
    }

    /** The servable route recipes — exposed so the shipped JSON Schema is drift-tested. */
    public static Set<String> knownRouteRecipes() {
        return RouteRules.KNOWN_ROUTE_RECIPES;
    }

    private static final Set<String> KNOWN_AUTH_MODES = Set.of("bearer", "browser", "api-key",
            "mtls", "public");

    /**
     * The route auth modes — exposed so the shipped JSON Schema's {@code security.auth} enum and
     * the Studio route form are drift-tested against one source (roadmap Phase 57; the hand-coded
     * form list had already lost {@code public}).
     */
    public static Set<String> knownAuthModes() {
        return KNOWN_AUTH_MODES;
    }

    private static final Set<String> KNOWN_INPUT_TYPES = io.tesseraql.yaml.app.DeclaredKinds.INPUT_TYPES;

    /**
     * Every {@code type:} an {@code inputField} shape may carry — the input types plus the
     * read-only {@code json} a {@code result:} entry or a domain declares — which is what the
     * shipped schema's enum lists, since one shape serves {@code input:}, {@code domains/} and
     * {@code result:} alike; which surface honours which is {@code DeclaredKinds}' judgement.
     */
    private static final Set<String> KNOWN_FIELD_TYPES = java.util.stream.Stream
            .concat(KNOWN_INPUT_TYPES.stream(),
                    io.tesseraql.yaml.app.DeclaredKinds.RESULT_KINDS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** The declared-input types — exposed for the same drift tests as {@link #knownAuthModes()}. */
    public static Set<String> knownInputTypes() {
        return KNOWN_INPUT_TYPES;
    }

    /** The union the {@code inputField} schema enum lists (see {@link #knownInputTypes()}). */
    public static Set<String> knownFieldTypes() {
        return KNOWN_FIELD_TYPES;
    }

    /** Loads and lints the app home, returning all findings. */
    public List<LintFinding> lint(Path appHome) {
        return lint(appHome, ExpressionFunctions.processDefault());
    }

    /**
     * As {@link #lint(Path)}, resolving custom expression calls against {@code functions}; the
     * codec set is the thread context loader's, spelled out — on the CLI the loader
     * {@code CliModules} composed over the application's modules (docs/codec-discovery.md
     * decision 1).
     */
    public List<LintFinding> lint(Path appHome, ExpressionFunctions functions) {
        return lint(appHome, functions, io.tesseraql.core.files.FileCodecs
                .discover(Thread.currentThread().getContextClassLoader()));
    }

    /**
     * As {@link #lint(Path, ExpressionFunctions)}, judging every export and import format
     * against {@code codecs} — a runtime's workshop passes the application's own set, so its
     * health lint sees exactly the codecs the application serves with.
     */
    public List<LintFinding> lint(Path appHome, ExpressionFunctions functions,
            io.tesseraql.core.files.FileCodecs codecs) {
        // The manifest loader absolutizes every source path; a relative app home (the
        // documented `tesseraql lint --app .` form) must match, or relativizing the
        // sources for finding locations throws.
        appHome = appHome.toAbsolutePath().normalize();
        AppManifest manifest = new ManifestLoader().load(appHome, functions);
        List<LintFinding> findings = new ArrayList<>();
        LintContext context = new LintContext(appHome, findings,
                io.tesseraql.yaml.catalog.Catalogs.load(appHome).all().values().stream()
                        .flatMap(spec -> spec.sourceTables().stream())
                        .collect(java.util.stream.Collectors
                                .toCollection(java.util.LinkedHashSet::new)),
                functions, codecs);
        for (LintRule rule : rules()) {
            rule.lint(context, manifest, findings);
        }
        return findings;
    }
}
