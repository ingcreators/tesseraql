package io.tesseraql.test;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.identity.IdentityContracts;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import io.tesseraql.yaml.model.TransitionSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives manifest-based coverage kinds from the app manifest and the declarative test suites
 * (design ch. 14): route, security, SAML, SCIM, and validation coverage. All of them reuse the
 * {@link ItemCoverage} model.
 *
 * <p>A route (and with it the security declaration it carries) counts as covered when a suite case
 * exercises one of the SQL artifacts the route executes — its {@code sql} file or contract, any
 * named {@code queries} entry, or the {@code import}/{@code export} SQL of a file-transfer route.
 * SAML coverage declares the Identity SQL Contracts the SAML login path executes when user linking
 * is enabled; SCIM coverage declares the contract SQL files wired through
 * {@code tesseraql.scim.*} configuration. A kind with nothing declared (for example SCIM coverage
 * in an app without SCIM) reports a 1.0 ratio.
 */
public final class ManifestCoverage {

    private static final List<String> SCIM_USER_OPS = List.of(
            "create", "findById", "list", "replace", "delete", "findByUserName", "count");
    private static final List<String> SCIM_GROUP_OPS = List.of(
            "create", "findById", "list", "replace", "delete",
            "listMembers", "addMember", "removeMember", "count");

    private ManifestCoverage() {
    }

    /** Route coverage: every manifest route declared, routes whose SQL artifacts ran covered. */
    public static ItemCoverage routes(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("route", manifest, suites, definition -> true);
    }

    /** Security coverage: routes declaring a {@code security:} block, covered like routes. */
    public static ItemCoverage security(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("security", manifest, suites, definition -> definition.security() != null);
    }

    /**
     * API-key coverage (roadmap Phase 25): routes authenticated by {@code auth: apiKey} — the
     * service-caller surface — declared and covered like routes, so a suite must exercise every
     * API-key-protected route.
     */
    public static ItemCoverage apiKey(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("api-key", manifest, suites, definition -> definition.security() != null
                && "apiKey".equals(definition.security().auth()));
    }

    /**
     * mTLS coverage (roadmap Phase 25): routes authenticated by {@code auth: mtls} — the
     * client-certificate service-caller surface — declared and covered like routes, so a suite must
     * exercise every mTLS-protected route.
     */
    public static ItemCoverage mtls(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("mtls", manifest, suites, definition -> definition.security() != null
                && "mtls".equals(definition.security().auth()));
    }

    /**
     * Webhook coverage (roadmap Phase 26): routes using the inbound {@code webhook} recipe —
     * declared and covered like routes (a webhook is a SQL pipeline behind HMAC verification), so a
     * suite must exercise every webhook route's SQL.
     */
    public static ItemCoverage webhook(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("webhook", manifest, suites,
                definition -> "webhook".equals(definition.recipe()));
    }

    /**
     * View coverage (roadmap Phase 39): routes rendering a declarative view
     * ({@code response.html.view}) — declared and covered like routes, so a suite must exercise
     * every view-backed route. Gated via {@code coverage.thresholds.view}.
     */
    public static ItemCoverage view(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("view", manifest, suites, definition -> definition.response() != null
                && definition.response().html() != null
                && definition.response().html().view() != null);
    }

    /**
     * Page coverage (roadmap Phase 41): routes declaring {@code page:} — declared and covered
     * like routes, so a suite must exercise every paginated query. Gated via
     * {@code coverage.thresholds.page}.
     */
    public static ItemCoverage page(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("page", manifest, suites,
                definition -> definition.page() != null);
    }

    /**
     * Queue-consume coverage (roadmap Phase 27): every {@code queue-consume} route under
     * {@code consume/} is declared, and one counts as covered when a suite exercises its SQL — the
     * same SQL-file basis as route coverage, since a consumer is a command pipeline triggered by a
     * message. Gated via {@code coverage.thresholds.queue-consume}.
     */
    public static ItemCoverage queueConsume(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("queue-consume");
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        for (RouteFile consumer : manifest.consumers()) {
            RouteDefinition definition = consumer.definition();
            if (definition.id() == null) {
                continue;
            }
            coverage.declare(definition.id());
            if (index.exercises(consumer)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    private static final Pattern SCOPE_DIRECTIVE = Pattern
            .compile("/\\*%\\s*scope\\s+([^*]+?)\\s*\\*/");

    /**
     * Data-scope coverage (roadmap Phase 29): every scope declared under {@code scope/} is declared,
     * and a scope counts as covered when a suite exercises a route (or consumer) whose SQL applies it
     * through a {@code /*%scope name%/} directive - the same SQL-file basis as route coverage. An app
     * with no scopes declares nothing and so reports a 1.0 ratio.
     */
    public static ItemCoverage dataScope(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("data-scope");
        Set<String> declared = new LinkedHashSet<>();
        for (ScopeFile scope : manifest.scopes()) {
            if (scope.definition().id() != null) {
                declared.add(scope.definition().id());
                coverage.declare(scope.definition().id());
            }
        }
        if (declared.isEmpty()) {
            return coverage;
        }
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        List<RouteFile> all = new ArrayList<>(manifest.routes());
        all.addAll(manifest.consumers());
        for (RouteFile route : all) {
            if (!index.exercises(route)) {
                continue;
            }
            for (String name : referencedScopes(route)) {
                if (declared.contains(name)) {
                    coverage.cover(name);
                }
            }
        }
        return coverage;
    }

    /**
     * Workflow coverage (roadmap Phase 28): every transition declared under {@code workflow/} is
     * declared as {@code <workflowId>#<transitionId>}, and a transition counts as covered when a
     * suite exercises its command SQL - the same SQL-file basis as route and {@code data-scope}
     * coverage. An app with no workflows declares nothing and so reports a 1.0 ratio.
     */
    public static ItemCoverage workflow(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("workflow");
        if (manifest.workflows().isEmpty()) {
            return coverage;
        }
        Set<Path> testedPaths = CrossReferenceIndex.of(manifest, suites).testedSqlPaths();
        for (WorkflowFile workflow : manifest.workflows()) {
            Path dir = workflow.source().getParent();
            String workflowId = workflow.definition().id();
            for (TransitionSpec transition : workflow.definition().transitions()) {
                if (transition.id() == null) {
                    continue;
                }
                String item = workflowId + "#" + transition.id();
                coverage.declare(item);
                if (transition.command() != null
                        && testedPaths.contains(dir.resolve(transition.command()).normalize())) {
                    coverage.cover(item);
                }
            }
        }
        return coverage;
    }

    /** The scope ids a route applies, scanned from {@code /*%scope%/} directives in its SQL files. */
    private static Set<String> referencedScopes(RouteFile route) {
        Set<String> names = new LinkedHashSet<>();
        Path dir = route.source().getParent();
        for (SqlBinding binding : CrossReferenceIndex.bindings(route.definition())) {
            if (binding.file() == null) {
                continue;
            }
            Path file = dir.resolve(binding.file());
            if (!Files.isRegularFile(file)) {
                continue;
            }
            Matcher matcher = SCOPE_DIRECTIVE.matcher(readQuietly(file));
            while (matcher.find()) {
                String content = matcher.group(1).trim();
                // Drop the `as boolean` suffix so a scope-flag directive resolves to its scope name.
                if (content.endsWith(" as boolean")) {
                    content = content.substring(0, content.length() - " as boolean".length())
                            .trim();
                }
                int on = content.indexOf(" on ");
                names.add(on >= 0 ? content.substring(0, on).trim() : content);
            }
        }
        return names;
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException ex) {
            return "";
        }
    }

    /**
     * MCP-tool coverage (roadmap Phase 24 follow-on): every application-declared tool under
     * {@code mcp/} is declared, and a tool counts as covered when a suite exercises one of its SQL
     * artifacts - the same SQL-file basis as route coverage, since a tool is a query/command.
     */
    public static ItemCoverage mcp(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("mcp");
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            RouteDefinition definition = tool.definition();
            if (definition.id() == null) {
                continue;
            }
            coverage.declare(definition.id());
            if (index.exercises(tool.source().getParent(), definition)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    /**
     * MCP-resource coverage (roadmap Phase 24): every application-declared resource under
     * {@code mcp/} is declared, and a resource counts as covered when a suite exercises one of its
     * SQL artifacts - the same SQL-file basis as route and tool coverage, since a resource is a
     * read-only query. Gated via {@code coverage.thresholds.mcp-resource}.
     */
    public static ItemCoverage resources(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("mcp-resource");
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            RouteDefinition definition = resource.definition();
            if (definition.id() == null) {
                continue;
            }
            coverage.declare(definition.id());
            if (index.exercises(resource.source().getParent(), definition)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    /**
     * MCP Apps UI coverage (roadmap Phase 24): every application-declared UI resource under
     * {@code mcp/} ({@code kind: ui}) is declared, and a UI resource counts as covered when a suite
     * exercises one of its SQL artifacts - the same SQL-file basis as route, tool, and resource
     * coverage, since a UI resource is a read-only query that renders an {@code hc-*} fragment.
     * Gated via {@code coverage.thresholds.mcp-ui}.
     */
    public static ItemCoverage uiResources(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("mcp-ui");
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            RouteDefinition definition = ui.definition();
            if (definition.id() == null) {
                continue;
            }
            coverage.declare(definition.id());
            if (index.exercises(ui.source().getParent(), definition)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    /**
     * Validation coverage (roadmap Phase 19): every rule of every route's {@code validate:}
     * block is declared as {@code <routeId>.<ruleId>}, and a suite's validation case covers the
     * rules it evaluates — the targeted rule, or the route's whole block when no rule is named.
     */
    public static ItemCoverage validation(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("validation");
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null) {
                continue;
            }
            definition.validate().keySet()
                    .forEach(ruleId -> coverage.declare(definition.id() + "." + ruleId));
        }
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.validate() == null || test.validate().route() == null) {
                    continue;
                }
                String prefix = test.validate().route() + ".";
                String targeted = test.validate().rule();
                coverage.declared().stream()
                        .filter(item -> item.startsWith(prefix))
                        .filter(item -> targeted == null || item.equals(prefix + targeted))
                        .forEach(coverage::cover);
            }
        }
        return coverage;
    }

    /**
     * Notification coverage (roadmap Phase 20): every notification of every route's
     * {@code notify:} block is declared as {@code <routeId>.<notifyId>}, and every job notify
     * step as {@code <jobId>.<stepId>}; a suite's notify case covers the declarations it
     * evaluates — the targeted one, or the route's/job's whole set when no id is named.
     */
    public static ItemCoverage notification(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("notification");
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null) {
                continue;
            }
            definition.notifications().keySet()
                    .forEach(notifyId -> coverage.declare(definition.id() + "." + notifyId));
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            if (job.definition().id() == null) {
                continue;
            }
            job.definition().effectiveSteps().stream()
                    .filter(step -> step.notification() != null)
                    .forEach(step -> coverage.declare(job.definition().id() + "." + step.id()));
        }
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                TestSuite.NotifyTarget target = test.notifications();
                if (target == null) {
                    continue;
                }
                String owner = target.route() != null ? target.route() : target.job();
                if (owner == null) {
                    continue;
                }
                String prefix = owner + ".";
                coverage.declared().stream()
                        .filter(item -> item.startsWith(prefix))
                        .filter(item -> target.id() == null || item.equals(prefix + target.id()))
                        .forEach(coverage::cover);
            }
        }
        return coverage;
    }

    /**
     * file-poll coverage (roadmap Phase 26): every {@code poll:}-triggered file-import job is
     * declared by its id and covered when a suite exercises its per-row import SQL — the same
     * SQL-file basis as route and document coverage, since a poll job is a file-import driven by a
     * directory watch instead of an upload.
     */
    public static ItemCoverage filePoll(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("file-poll");
        Set<Path> testedPaths = CrossReferenceIndex.of(manifest, suites).testedSqlPaths();
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            io.tesseraql.yaml.model.JobDefinition definition = job.definition();
            if (definition.id() == null || definition.trigger() == null
                    || definition.trigger().poll() == null) {
                continue;
            }
            coverage.declare(definition.id());
            io.tesseraql.yaml.model.ImportSpec importSpec = definition.fileImport();
            if (importSpec != null && importSpec.sql() != null && importSpec.sql().file() != null
                    && testedPaths.contains(job.source().getParent()
                            .resolve(importSpec.sql().file()).normalize())) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    /**
     * http-call coverage (roadmap Phase 26): every job's {@code http-call:} pipeline step is
     * declared as {@code <jobId>.<stepId>}; a suite's http-call case covers the steps it plans —
     * the targeted one, or the job's whole set when no id is named — so a managed outbound
     * connector is exercised before it ships.
     */
    public static ItemCoverage httpCall(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("http-call");
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            if (job.definition().id() == null) {
                continue;
            }
            job.definition().effectiveSteps().stream()
                    .filter(step -> step.httpCall() != null)
                    .forEach(step -> coverage.declare(job.definition().id() + "." + step.id()));
        }
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                TestSuite.HttpCallTarget target = test.httpCall();
                if (target == null || target.job() == null) {
                    continue;
                }
                String prefix = target.job() + ".";
                coverage.declared().stream()
                        .filter(item -> item.startsWith(prefix))
                        .filter(item -> target.id() == null || item.equals(prefix + target.id()))
                        .forEach(coverage::cover);
            }
        }
        return coverage;
    }

    /**
     * Document coverage (roadmap Phase 21): every route exporting a printable document
     * ({@code format: pdf} on {@code query-export}/{@code file-export}) is declared and covered
     * like routes - when a suite case exercises one of its SQL artifacts, the extraction the
     * document renders is proven.
     */
    public static ItemCoverage document(AppManifest manifest, List<TestSuite> suites) {
        return routeKind("document", manifest, suites,
                definition -> definition.fileExport() != null
                        && "pdf".equals(definition.fileExport().format()));
    }

    /**
     * Message coverage (roadmap Phase 22): every {@code messages/<locale>.yml} catalog the app
     * ships is declared by its language tag, and a suite's messages case covers the catalogs its
     * locale resolves through — the exact tag and its bare language — when it asserts on the
     * texts. Gated via {@code coverage.thresholds.message}.
     */
    public static ItemCoverage message(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("message");
        io.tesseraql.yaml.i18n.MessageCatalog catalog = io.tesseraql.yaml.i18n.MessageCatalog
                .load(manifest.appHome().resolve("messages"));
        catalog.tags().forEach(coverage::declare);
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (test.messages() == null || test.messages().locale() == null) {
                    continue;
                }
                java.util.Locale locale = java.util.Locale
                        .forLanguageTag(test.messages().locale().trim());
                for (String tag : List.of(locale.toLanguageTag(), locale.getLanguage())) {
                    if (coverage.declared().contains(tag)) {
                        coverage.cover(tag);
                    }
                }
            }
        }
        return coverage;
    }

    /**
     * SAML coverage: when SAML user linking is enabled ({@code tesseraql.saml.link.enabled}), the
     * SAML login path resolves the principal through Identity SQL Contracts — those contracts are
     * declared and contract test cases cover them. Without SAML (or without linking) the login
     * path executes no app-authored SQL, so nothing is declared.
     */
    public static ItemCoverage saml(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("saml");
        AppConfig config = manifest.config();
        if (flag(config, "tesseraql.saml.enabled") && flag(config, "tesseraql.saml.link.enabled")) {
            coverage.declareAll(List.of(IdentityContracts.FIND_USER_BY_LOGIN,
                    IdentityContracts.FIND_GROUPS_BY_USER_ID,
                    IdentityContracts.FIND_ROLES_BY_USER_ID,
                    IdentityContracts.FIND_PERMISSIONS_BY_USER_ID));
            if (flag(config, "tesseraql.saml.link.provision")) {
                coverage.declare(IdentityContracts.CREATE_USER);
            }
            CrossReferenceIndex.of(manifest, suites).testedContracts().stream()
                    .filter(coverage.declared()::contains).forEach(coverage::cover);
        }
        return coverage;
    }

    /**
     * OIDC coverage (roadmap Phase 25): when the relying party links federated logins to local
     * users, the identity contracts the login path runs are declared and contract test cases cover
     * them. Without OIDC (or without linking) the login path executes no app-authored SQL, so
     * nothing is declared. Mirrors {@link #saml(AppManifest, List)}.
     */
    /**
     * Declared preference groups (roadmap Phase 48 slice 5): a NOTE kind listing the
     * {@code config/preferences.yml} keys, so the report shows what the account surface
     * offers. Preferences are configuration, not an executable surface - nothing covers
     * them, which is exactly what the note level communicates.
     */
    public static ItemCoverage preference(AppManifest manifest) {
        ItemCoverage coverage = new ItemCoverage("preference");
        io.tesseraql.yaml.account.PreferencesSpec.load(manifest.appHome()).fields()
                .forEach(field -> coverage.declare("app." + field.key()));
        return coverage;
    }

    public static ItemCoverage oidc(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("oidc");
        AppConfig config = manifest.config();
        if (flag(config, "tesseraql.oidc.enabled") && flag(config, "tesseraql.oidc.link.enabled")) {
            coverage.declareAll(List.of(IdentityContracts.FIND_USER_BY_LOGIN,
                    IdentityContracts.FIND_GROUPS_BY_USER_ID,
                    IdentityContracts.FIND_ROLES_BY_USER_ID,
                    IdentityContracts.FIND_PERMISSIONS_BY_USER_ID));
            if (flag(config, "tesseraql.oidc.link.provision")) {
                coverage.declare(IdentityContracts.CREATE_USER);
            }
            CrossReferenceIndex.of(manifest, suites).testedContracts().stream()
                    .filter(coverage.declared()::contains).forEach(coverage::cover);
        }
        return coverage;
    }

    /**
     * SCIM coverage: the contract SQL files wired through {@code tesseraql.scim.users.*} (and
     * {@code tesseraql.scim.groups.*} when group provisioning is on) are declared per operation,
     * and a SQL test case exercising the configured file covers it.
     */
    public static ItemCoverage scim(AppManifest manifest, List<TestSuite> suites) {
        ItemCoverage coverage = new ItemCoverage("scim");
        AppConfig config = manifest.config();
        if (!flag(config, "tesseraql.scim.enabled")) {
            return coverage;
        }
        Set<Path> tested = CrossReferenceIndex.of(manifest, suites).testedSqlPaths();
        declareScimOps(coverage, manifest, "users", SCIM_USER_OPS, tested);
        if (flag(config, "tesseraql.scim.groups.enabled")) {
            declareScimOps(coverage, manifest, "groups", SCIM_GROUP_OPS, tested);
        }
        return coverage;
    }

    private static void declareScimOps(ItemCoverage coverage, AppManifest manifest, String resource,
            List<String> operations, Set<Path> testedPaths) {
        for (String operation : operations) {
            String item = resource + "." + operation;
            manifest.config().getString("tesseraql.scim." + item).ifPresent(sqlFile -> {
                coverage.declare(item);
                if (testedPaths.contains(manifest.appHome().resolve(sqlFile).normalize())) {
                    coverage.cover(item);
                }
            });
        }
    }

    private static ItemCoverage routeKind(String kind, AppManifest manifest, List<TestSuite> suites,
            Predicate<RouteDefinition> declared) {
        ItemCoverage coverage = new ItemCoverage(kind);
        CrossReferenceIndex index = CrossReferenceIndex.of(manifest, suites);
        for (RouteFile route : manifest.routes()) {
            RouteDefinition definition = route.definition();
            if (definition.id() == null || !declared.test(definition)) {
                continue;
            }
            coverage.declare(definition.id());
            if (index.exercises(route)) {
                coverage.cover(definition.id());
            }
        }
        return coverage;
    }

    private static boolean flag(AppConfig config, String key) {
        return config.getString(key).map(Boolean::parseBoolean).orElse(false);
    }
}
