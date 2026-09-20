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
                new ConditionZoneRules(),
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
        List<LintFinding> findings = new ArrayList<>();
        // The tolerant load (docs/audit-low-leads.md slice 8): a document that does not parse is
        // one finding at that document, and every other document is still linted. The strict
        // load used to escape here as the parser's exception, so the lint reported nothing at
        // all — no finding, no JSON document for the editor — on the most ordinary mid-edit
        // shape, an empty file whose header is not typed yet. What still fails the load whole
        // is what every document resolves through (the configuration, a shared definition);
        // that is one finding too, at the file it names, so the lint's contract — a findings
        // document — holds on every application.
        List<ManifestLoader.BrokenDocument> broken = new ArrayList<>();
        AppManifest manifest;
        try {
            manifest = new ManifestLoader().load(appHome, broken, functions);
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(loadFailure(appHome, ex));
            return findings;
        }
        for (ManifestLoader.BrokenDocument document : broken) {
            findings.add(brokenDocument(appHome, document));
        }
        LintContext context = new LintContext(appHome, findings,
                io.tesseraql.yaml.catalog.Catalogs.load(appHome).all().values().stream()
                        .flatMap(spec -> spec.sourceTables().stream())
                        .collect(java.util.stream.Collectors
                                .toCollection(java.util.LinkedHashSet::new)),
                io.tesseraql.yaml.app.HeldSources.tables(manifest), functions, codecs);
        for (LintRule rule : rules()) {
            rule.lint(context, manifest, findings);
        }
        return findings;
    }

    /** A document the tolerant load left out, as the one finding that names it. */
    private static LintFinding brokenDocument(Path appHome,
            ManifestLoader.BrokenDocument document) {
        return new LintFinding(
                document.code() == null
                        ? io.tesseraql.yaml.SimpleYamlParser.SCHEMA_ERROR.toString()
                        : document.code(),
                LintFinding.Severity.ERROR, LintSupport.relative(appHome, document.source()),
                document.error(), parserLine(document.error()), parserColumn(document.error()));
    }

    /**
     * A refusal that failed the load whole, as the one finding that names its file — the
     * configuration or a shared definition, relative to the app home when it is inside it,
     * else {@code app}, the pseudo-source the Studio health dashboard already uses.
     */
    private static LintFinding loadFailure(Path appHome,
            io.tesseraql.core.error.TqlException ex) {
        return new LintFinding(ex.code().toString(), LintFinding.Severity.ERROR,
                ex.source().map(named -> insideAppHome(appHome, named)).orElse("app"),
                ex.sentence(), ex.line().orElseGet(() -> parserLine(ex.sentence())),
                parserColumn(ex.sentence()));
    }

    /**
     * The file a refusal names, relative to the app home when it is a file inside it; a
     * pseudo-source ({@code <string>}, a path outside the home, a name no file system accepts)
     * is {@code app}.
     */
    private static String insideAppHome(Path appHome, String named) {
        try {
            Path path = Path.of(named);
            if (!path.isAbsolute()) {
                return "app";
            }
            return io.tesseraql.core.files.ConfinedPath.under(appHome).confine(path)
                    .map(inside -> LintSupport.relative(appHome, inside))
                    .orElse("app");
        } catch (java.nio.file.InvalidPathException notAPath) {
            return "app";
        }
    }

    /** The YAML parser's own location, when the sentence carries one: {@code line: 5, column: 7}. */
    private static final java.util.regex.Pattern PARSER_LOCATION = java.util.regex.Pattern
            .compile("line: (\\d+), column: (\\d+)");

    private static Integer parserLine(String sentence) {
        java.util.regex.Matcher at = PARSER_LOCATION.matcher(sentence == null ? "" : sentence);
        return at.find() ? Integer.valueOf(at.group(1)) : null;
    }

    private static Integer parserColumn(String sentence) {
        java.util.regex.Matcher at = PARSER_LOCATION.matcher(sentence == null ? "" : sentence);
        return at.find() ? Integer.valueOf(at.group(2)) : null;
    }
}
