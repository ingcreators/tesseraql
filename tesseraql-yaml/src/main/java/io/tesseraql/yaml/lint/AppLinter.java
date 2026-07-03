package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.expr.ExpressionParser;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.manifest.ScopeFile;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.DeadlineSpec;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.MatchArm;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.ScopeDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import io.tesseraql.yaml.model.StateSpec;
import io.tesseraql.yaml.model.TransitionSpec;
import io.tesseraql.yaml.model.WhenCondition;
import io.tesseraql.yaml.model.WorkflowDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statically lints an app home, independent of Maven, so it is unit-testable (design ch. 18, 20).
 *
 * <p>The first rule set checks recipes are known, referenced SQL files exist, and route security
 * policies are defined (deny-by-default safety). More rules (large-data, tenant predicate, field
 * authorization) are added alongside their features.
 */
public final class AppLinter {

    private static final Set<String> KNOWN_ROUTE_RECIPES = Set.of("query-json", "command-json",
            "query-html", "page", "query-export", "file-import", "file-export", "webhook");
    /** Recipes an application-declared MCP tool may use (roadmap Phase 24 follow-on). */
    private static final Set<String> KNOWN_TOOL_RECIPES = Set.of("query-json", "command-json");
    /** Recipes an MCP Apps UI resource may use - both render HTML (roadmap Phase 24). */
    private static final Set<String> KNOWN_UI_RECIPES = Set.of("query-html", "page");
    /** Recipes a {@code publish:} block may ride - the transactional commands (roadmap Phase 27). */
    private static final Set<String> PUBLISH_RECIPES = Set.of("command-json", "webhook",
            "queue-consume");
    /** The MCP Apps uri scheme a UI resource is addressed by (SEP-1865). */
    private static final String UI_SCHEME = "ui://";

    /** Loads and lints the app home, returning all findings. */
    public List<LintFinding> lint(Path appHome) {
        AppManifest manifest = new ManifestLoader().load(appHome);
        List<LintFinding> findings = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            lintRoute(appHome, manifest.config(), route, findings);
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            lintJob(appHome, manifest.config(), job, findings);
        }
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            lintTool(appHome, manifest.config(), tool, findings);
        }
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            lintResource(appHome, manifest.config(), resource, findings);
        }
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            lintUiResource(appHome, manifest.config(), ui, findings);
        }
        for (RouteFile consumer : manifest.consumers()) {
            lintConsumer(appHome, manifest.config(), consumer, findings);
        }
        lintDuplicateResourceUris(appHome, manifest, findings);
        lintToolUiLinks(appHome, manifest, findings);
        lintI18n(appHome, manifest, findings);
        lintSecurityConfig(appHome, manifest, findings);
        lintScopes(appHome, manifest, findings);
        lintOrgUnitConfig(manifest.config(), findings);
        lintWorkflows(appHome, manifest, findings);
        lintWorkflowConfig(manifest.config(), findings);
        lintAttachments(appHome, manifest, findings);
        lintObjectStorageEgress(appHome, manifest, findings);
        lintViews(appHome, manifest, findings);
        return findings;
    }

    /**
     * Validates declarative views (roadmap Phase 39, docs/declarative-views.md): the
     * {@code response.html.view} reference resolves and parses ({@code TQL-VIEW-3301/3302}), is
     * not combined with {@code template:} ({@code TQL-VIEW-3302}), a form's {@code action:} names
     * a POST route with an {@code input:} block ({@code TQL-VIEW-3303}) whose fields the view's
     * {@code fields:} entries actually declare ({@code TQL-VIEW-3304}) with known widgets
     * ({@code TQL-VIEW-3305}); and an app's {@code templates/tql/view/*.html} pattern override
     * carries the expected fragment signature ({@code TQL-VIEW-3307}, warning).
     */
    private void lintViews(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            var html = response == null ? null : response.html();
            if (html == null || html.view() == null) {
                continue;
            }
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            if (html.template() != null) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "response.html declares both template: and view: — they are mutually"
                                + " exclusive"));
            }
            Path routeDir = route.source().getParent();
            Path colocated = routeDir.resolve(html.view()).normalize();
            Path file = java.nio.file.Files.isRegularFile(colocated)
                    ? colocated
                    : appHome.resolve("templates").resolve(html.view()).normalize();
            if (!java.nio.file.Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-VIEW-3302", "error", source,
                        "view: " + html.view() + " does not resolve (colocated or templates/)"));
                continue;
            }
            io.tesseraql.yaml.view.ViewSpec spec;
            try {
                spec = io.tesseraql.yaml.view.ViewSpec.parse(file);
            } catch (io.tesseraql.core.error.TqlException ex) {
                findings.add(new LintFinding("TQL-VIEW-3301", "error", source, ex.getMessage()));
                continue;
            }
            if (io.tesseraql.yaml.view.ViewSpec.FORM.equals(spec.view())) {
                lintFormView(manifest, source, spec, findings);
            }
        }
        lintViewOverrides(appHome, findings);
    }

    /** A form view's action route exists, declares inputs, and covers every fields: entry. */
    private void lintFormView(AppManifest manifest, String source,
            io.tesseraql.yaml.view.ViewSpec spec, List<LintFinding> findings) {
        RouteFile action = null;
        for (RouteFile candidate : manifest.routes()) {
            if ("POST".equalsIgnoreCase(candidate.httpMethod())
                    && candidate.urlPath().equals(spec.action())) {
                action = candidate;
                break;
            }
        }
        if (action == null) {
            findings.add(new LintFinding("TQL-VIEW-3303", "error", source,
                    "view " + spec.id() + ": action " + spec.action()
                            + " matches no POST route"));
            return;
        }
        var inputs = action.definition().input();
        if (inputs == null || inputs.isEmpty()) {
            findings.add(new LintFinding("TQL-VIEW-3303", "error", source,
                    "view " + spec.id() + ": action route " + action.definition().id()
                            + " declares no input: block to derive fields from"));
            return;
        }
        for (io.tesseraql.yaml.view.ViewSpec.Field field : spec.fields()) {
            if (!inputs.containsKey(field.name())) {
                findings.add(new LintFinding("TQL-VIEW-3304", "error", source,
                        "view " + spec.id() + ": field " + field.name()
                                + " is not declared by the action route's input: block"));
            }
            if (field.widget() != null
                    && !io.tesseraql.yaml.view.ViewSpec.WIDGETS.contains(field.widget())) {
                findings.add(new LintFinding("TQL-VIEW-3305", "error", source,
                        "view " + spec.id() + ": unknown widget " + field.widget()
                                + " (known: " + io.tesseraql.yaml.view.ViewSpec.WIDGETS + ")"));
            }
        }
    }

    /**
     * An L2 pattern override must carry the pattern's fragment signature so it stays compatible
     * with fragment-level composition (docs/declarative-views.md; warning, not error — the whole
     * file still renders today).
     */
    private void lintViewOverrides(Path appHome, List<LintFinding> findings) {
        Path overrides = appHome.resolve("templates").resolve("tql").resolve("view");
        if (!java.nio.file.Files.isDirectory(overrides)) {
            return;
        }
        try (var files = java.nio.file.Files.list(overrides)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".html"))
                    .sorted().toList()) {
                String name = file.getFileName().toString();
                String expected = name.startsWith("field")
                        ? "th:fragment=\"field(f)\""
                        : "th:fragment=\"view(v)\"";
                String content = java.nio.file.Files.readString(file);
                if (!content.contains(expected)) {
                    findings.add(new LintFinding("TQL-VIEW-3307", "warning",
                            appHome.relativize(file).toString().replace('\\', '/'),
                            "view pattern override lacks the expected " + expected
                                    + " signature (docs/declarative-views.md)"));
                }
            }
        } catch (java.io.IOException ex) {
            findings.add(new LintFinding("TQL-VIEW-3307", "warning", "templates/tql/view",
                    "view pattern overrides could not be read: " + ex.getMessage()));
        }
    }

    /** Validates approval-workflow configuration (roadmap Phase 28): a known {@code mode}. */
    private void lintWorkflowConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.workflow.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding("TQL-WORKFLOW-3110", "error", "config",
                    "tesseraql.workflow.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }

    /** Validates org-unit configuration (roadmap Phase 29 slice 2): a known {@code mode}. */
    private void lintOrgUnitConfig(AppConfig config, List<LintFinding> findings) {
        String mode = config.getString("tesseraql.orgunit.mode").orElse(null);
        if (mode != null && !"managed".equalsIgnoreCase(mode) && !"app".equalsIgnoreCase(mode)) {
            findings.add(new LintFinding("TQL-SCOPE-3020", "error", "config",
                    "tesseraql.orgunit.mode must be 'managed' or 'app', not '" + mode + "'"));
        }
    }

    /**
     * Lints authentication configuration (roadmap Phase 25): a bearer JWT picks a supported
     * algorithm and a single matching key source (no algorithm confusion), and an
     * {@code auth: apiKey} route requires API-key config whose clients each store a key hash. Reads
     * raw config nodes — never resolving secret placeholders — so the lint runs without a live
     * secret store.
     */
    private void lintSecurityConfig(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        AppConfig config = manifest.config();
        if (config.navigate("tesseraql.security.jwt") != null) {
            lintJwtConfig(config, findings);
        }
        lintApiKeyConfig(appHome, manifest, config, findings);
        lintMtlsConfig(appHome, manifest, config, findings);
        lintOidcConfig(config, findings);
    }

    /**
     * Lints the OIDC relying-party config (roadmap Phase 25): when enabled, it must declare a
     * https (or loopback-http) discovery URI, a client id, and a redirect URI — caught statically
     * so a misconfigured login fails at lint, not at the first redirect. Reads raw config nodes.
     */
    private void lintOidcConfig(AppConfig config, List<LintFinding> findings) {
        if (!"true".equalsIgnoreCase(rawString(config, "tesseraql.oidc.enabled"))) {
            return;
        }
        String discoveryUri = rawString(config, "tesseraql.oidc.discoveryUri");
        if (discoveryUri == null) {
            findings.add(new LintFinding("TQL-SEC-4050", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.discoveryUri is not configured"));
        } else if (!discoveryUri.contains("${") && !isHttpsOrLoopback(discoveryUri)) {
            findings.add(new LintFinding("TQL-SEC-4051", "error", "config",
                    "OIDC tesseraql.oidc.discoveryUri must be https"
                            + " (loopback http is allowed for development)"));
        }
        if (rawString(config, "tesseraql.oidc.clientId") == null) {
            findings.add(new LintFinding("TQL-SEC-4052", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.clientId is not configured"));
        }
        if (rawString(config, "tesseraql.oidc.redirectUri") == null) {
            findings.add(new LintFinding("TQL-SEC-4053", "error", "config",
                    "OIDC is enabled but tesseraql.oidc.redirectUri is not configured"));
        }
    }

    private static String rawString(AppConfig config, String path) {
        Object value = config.navigate(path);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isHttpsOrLoopback(String uri) {
        if (uri.startsWith("https://")) {
            return true;
        }
        if (uri.startsWith("http://")) {
            String host = uri.substring("http://".length());
            return host.startsWith("localhost") || host.startsWith("127.0.0.1")
                    || host.startsWith("[::1]") || host.startsWith("::1");
        }
        return false;
    }

    private void lintJwtConfig(AppConfig config, List<LintFinding> findings) {
        Object rawAlgorithm = config.navigate("tesseraql.security.jwt.algorithm");
        String algorithm = rawAlgorithm == null
                ? "HS256"
                : String.valueOf(rawAlgorithm).toUpperCase(java.util.Locale.ROOT);
        boolean secret = config.navigate("tesseraql.security.jwt.secret") != null;
        boolean publicKey = config.navigate("tesseraql.security.jwt.publicKey") != null;
        boolean jwksUri = config.navigate("tesseraql.security.jwt.jwksUri") != null;
        boolean keyMaterial = publicKey || jwksUri;
        if (!algorithm.equals("HS256") && !algorithm.equals("RS256")) {
            findings.add(new LintFinding("TQL-SEC-4043", "error", "config",
                    "Unsupported JWT algorithm '" + algorithm + "'; use HS256 or RS256"));
            return;
        }
        if (algorithm.equals("HS256") && keyMaterial) {
            findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                    "JWT algorithm HS256 declares RS256 key material (publicKey/jwksUri); an"
                            + " algorithm-confusion risk - pick one algorithm"));
        }
        if (algorithm.equals("RS256")) {
            if (secret) {
                findings.add(new LintFinding("TQL-SEC-4042", "error", "config",
                        "JWT algorithm RS256 declares an HS256 secret; an algorithm-confusion risk"
                                + " - pick one algorithm"));
            }
            if (!keyMaterial) {
                findings.add(new LintFinding("TQL-SEC-4040", "error", "config",
                        "RS256 JWT config must declare a key source (jwksUri or publicKey)"));
            } else if (publicKey && jwksUri) {
                findings.add(new LintFinding("TQL-SEC-4041", "error", "config",
                        "RS256 JWT config declares conflicting key sources; set exactly one of"
                                + " jwksUri/publicKey"));
            }
        }
    }

    private void lintApiKeyConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        boolean apiKeysConfigured = config.navigate("tesseraql.security.apiKeys") != null;
        if (!apiKeysConfigured) {
            for (RouteFile route : manifest.routes()) {
                io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
                if (security != null && "apiKey".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding("TQL-SEC-4044", "error", source,
                            "Route '" + route.definition().id() + "' declares auth: apiKey but no"
                                    + " tesseraql.security.apiKeys is configured (deny by default)"));
                }
            }
            return;
        }
        if (!(config.navigate(
                "tesseraql.security.apiKeys.clients") instanceof java.util.Map<?, ?> clients)) {
            return;
        }
        clients.forEach((id, spec) -> {
            java.util.Map<?, ?> client = spec instanceof java.util.Map<?, ?> map
                    ? map
                    : java.util.Map.of();
            if (config.navigate(
                    "tesseraql.security.apiKeys.clients." + id + ".secretHash") == null) {
                findings.add(new LintFinding("TQL-SEC-4045", "error", "config",
                        "API-key client '" + id + "' must declare a secretHash; raw keys are never"
                                + " stored"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding("TQL-SEC-4046", "warning", "config",
                        "API-key client '" + id + "' grants no roles or permissions; service"
                                + " callers should be least-privilege"));
            }
        });
    }

    /**
     * Lints the mutual-TLS config (roadmap Phase 25): an {@code auth: mtls} route requires mTLS
     * config; the config must name the forwarded-certificate header and each client must declare
     * exactly one certificate matcher (subjectDn/san/sha256). A missing trustBundle is a warning —
     * without it the runtime does not independently validate the chain and fully trusts the
     * TLS-terminating edge. Reads raw config nodes — never resolving secret placeholders.
     */
    private void lintMtlsConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        if (config.navigate("tesseraql.security.mtls") == null) {
            for (RouteFile route : manifest.routes()) {
                io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
                if (security != null && "mtls".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding("TQL-SEC-4060", "error", source,
                            "Route '" + route.definition().id() + "' declares auth: mtls but no"
                                    + " tesseraql.security.mtls is configured (deny by default)"));
                }
            }
            return;
        }
        if (config.navigate("tesseraql.security.mtls.forwardedHeader") == null) {
            findings.add(new LintFinding("TQL-SEC-4061", "error", "config",
                    "tesseraql.security.mtls declares no forwardedHeader; a forwarded client"
                            + " certificate has no header to be read from"));
        }
        if (config.navigate("tesseraql.security.mtls.trustBundle") == null) {
            findings.add(new LintFinding("TQL-SEC-4065", "warning", "config",
                    "tesseraql.security.mtls declares no trustBundle; the runtime does not"
                            + " independently validate the certificate chain and fully trusts the"
                            + " TLS-terminating edge"));
        }
        if (!(config.navigate(
                "tesseraql.security.mtls.clients") instanceof java.util.Map<?, ?> clients)) {
            return;
        }
        clients.forEach((id, spec) -> {
            java.util.Map<?, ?> client = spec instanceof java.util.Map<?, ?> map
                    ? map
                    : java.util.Map.of();
            int matchers = 0;
            if (client.get("subjectDn") != null) {
                matchers++;
            }
            if (client.get("san") != null) {
                matchers++;
            }
            if (client.get("sha256") != null) {
                matchers++;
            }
            if (matchers == 0) {
                findings.add(new LintFinding("TQL-SEC-4062", "error", "config",
                        "mTLS client '" + id + "' declares no certificate matcher; set exactly one"
                                + " of subjectDn/san/sha256"));
            } else if (matchers > 1) {
                findings.add(new LintFinding("TQL-SEC-4063", "error", "config",
                        "mTLS client '" + id + "' declares more than one certificate matcher; set"
                                + " exactly one of subjectDn/san/sha256"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding("TQL-SEC-4064", "warning", "config",
                        "mTLS client '" + id + "' grants no roles or permissions; service callers"
                                + " should be least-privilege"));
            }
        });
    }

    /**
     * Lints an application-declared MCP Apps UI resource (roadmap Phase 24): it renders HTML (the
     * {@code query-html} or {@code page} recipe), declares a {@code ui://} uri the client reads and
     * tools link to, takes no {@code input:} (a UI resource is addressed only by its uri), its SQL
     * file exists, and its referenced policy is defined. A missing description is a warning: it is
     * the hint the model uses to decide whether to surface the UI.
     */
    private void lintUiResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.UiResourceFile ui, List<LintFinding> findings) {
        RouteDefinition definition = ui.definition();
        String source = appHome.relativize(ui.source()).toString().replace('\\', '/');

        if (!KNOWN_UI_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-MCP-1008", "error", source,
                    "MCP UI resource '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; a UI resource renders HTML - use query-html or page"));
        }
        if (ui.uri() == null || !ui.uri().startsWith(UI_SCHEME)) {
            findings.add(new LintFinding("TQL-MCP-1009", "error", source,
                    "MCP UI resource '" + definition.id() + "' must declare a ui:// uri: it is the"
                            + " address the client reads and a tool links to"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding("TQL-MCP-1011", "error", source,
                    "MCP UI resource '" + definition.id()
                            + "' must not declare input: a UI resource"
                            + " is addressed only by its uri and takes no arguments"));
        }
        if (ui.description() == null || ui.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1010", "warning", source,
                    "MCP UI resource '" + definition.id() + "' has no description; it is the hint"
                            + " the model uses to decide whether to surface the UI"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(ui.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP UI resource references undefined policy '" + policy
                            + "' (deny by default)"));
        }
    }

    /**
     * A tool's {@code ui:} link must resolve to a UI resource the app declares; a dangling link
     * would advertise a {@code _meta.ui.resourceUri} no {@code resources/read} can serve. Fail fast
     * at lint time rather than at render.
     */
    private void lintToolUiLinks(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new java.util.HashSet<>();
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            if (ui.uri() != null) {
                declared.add(ui.uri());
            }
        }
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            String link = tool.uiResource();
            if (link != null && !link.isBlank() && !declared.contains(link)) {
                String source = appHome.relativize(tool.source()).toString().replace('\\', '/');
                findings.add(new LintFinding("TQL-MCP-1012", "error", source,
                        "MCP tool '" + tool.definition().id() + "' links ui: '" + link
                                + "' but no kind: ui resource declares that uri"));
            }
        }
    }

    /**
     * Lints an application-declared MCP resource (roadmap Phase 24): it is read-only (the
     * {@code query-json} recipe, query-mode SQL), declares a {@code uri} the client reads, takes no
     * {@code input:} (a resource is addressed only by its uri), its SQL file exists, and its
     * referenced policy is defined. A missing description is a warning: it is the hint the model
     * uses to decide whether to attach the resource as context.
     */
    private void lintResource(Path appHome, AppConfig config,
            io.tesseraql.yaml.manifest.ResourceFile resource, List<LintFinding> findings) {
        RouteDefinition definition = resource.definition();
        String source = appHome.relativize(resource.source()).toString().replace('\\', '/');

        boolean write = !"query-json".equals(definition.recipe())
                || (definition.sql() != null && "update".equals(definition.sql().effectiveMode()));
        if (write) {
            findings.add(new LintFinding("TQL-MCP-1003", "error", source,
                    "MCP resource '" + definition.id() + "' must be read-only: use the query-json"
                            + " recipe with query-mode SQL"));
        }
        if (resource.uri() == null || resource.uri().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1004", "error", source,
                    "MCP resource '" + definition.id() + "' must declare a uri: it is the address"
                            + " the client reads the resource by"));
        }
        if (!definition.input().isEmpty()) {
            findings.add(new LintFinding("TQL-MCP-1006", "error", source,
                    "MCP resource '" + definition.id() + "' must not declare input: a resource is"
                            + " addressed only by its uri and takes no arguments"));
        }
        if (resource.description() == null || resource.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1005", "warning", source,
                    "MCP resource '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide whether to attach the resource"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(
                        resource.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        String policy = definition.security() == null ? null : definition.security().policy();
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP resource references undefined policy '" + policy + "' (deny by default)"));
        }
    }

    /**
     * Two resources sharing a {@code uri} would collide at startup (the MCP server keys every
     * resource by its uri and rejects a duplicate), so flag it at lint time instead - deny by
     * default, fail fast. UI resources ({@code ui://}) share that single namespace with plain
     * resources, so they are checked together.
     */
    private void lintDuplicateResourceUris(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        java.util.Map<String, String> seen = new java.util.HashMap<>();
        for (io.tesseraql.yaml.manifest.ResourceFile resource : manifest.resources()) {
            checkDuplicateUri(appHome, resource.uri(), resource.source(), seen, findings);
        }
        for (io.tesseraql.yaml.manifest.UiResourceFile ui : manifest.uiResources()) {
            checkDuplicateUri(appHome, ui.uri(), ui.source(), seen, findings);
        }
    }

    private void checkDuplicateUri(Path appHome, String uri, Path file,
            java.util.Map<String, String> seen, List<LintFinding> findings) {
        if (uri == null || uri.isBlank()) {
            return;
        }
        String source = appHome.relativize(file).toString().replace('\\', '/');
        String previous = seen.putIfAbsent(uri, source);
        if (previous != null) {
            findings.add(new LintFinding("TQL-MCP-1007", "error", source,
                    "MCP resource uri '" + uri + "' is already declared by " + previous));
        }
    }

    /**
     * Lints an application-declared MCP tool (roadmap Phase 24 follow-on): its recipe is a tool
     * recipe, its SQL files exist, its referenced policy is defined, and - deny by default - a write
     * tool declares an authorization policy, since an AI agent must not mutate data unauthorized. A
     * missing description is a warning: it is the hint the model uses to decide when to call.
     */
    private void lintTool(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.ToolFile tool,
            List<LintFinding> findings) {
        RouteDefinition definition = tool.definition();
        String source = appHome.relativize(tool.source()).toString().replace('\\', '/');

        if (!KNOWN_TOOL_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-MCP-1001", "error", source,
                    "MCP tool '" + definition.id() + "' has recipe '" + definition.recipe()
                            + "'; only query-json and command-json are supported"));
        }
        if (tool.description() == null || tool.description().isBlank()) {
            findings.add(new LintFinding("TQL-MCP-1002", "warning", source,
                    "MCP tool '" + definition.id() + "' has no description; it is the hint the"
                            + " model uses to decide when to call the tool"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null
                && !Files.isRegularFile(
                        tool.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(tool.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });

        boolean write = "command-json".equals(definition.recipe())
                || (definition.sql() != null && "update".equals(definition.sql().effectiveMode()));
        String policy = definition.security() == null ? null : definition.security().policy();
        if (write && (policy == null || policy.isBlank())) {
            findings.add(new LintFinding("TQL-MCP-4030", "error", source,
                    "Write MCP tool '" + definition.id() + "' must declare a security.policy: an AI"
                            + " agent must not mutate data without authorization (deny by default)"));
        }
        if (policy != null && !policy.isBlank() && !policyDefined(config, policy)) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "MCP tool references undefined policy '" + policy + "' (deny by default)"));
        }
    }

    /**
     * Statically checks the app's message catalogs (roadmap Phase 22) when a {@code messages/}
     * directory exists: catalog files parse and carry valid BCP-47 names (TQL-YAML-1007), every
     * locale declared in {@code tesseraql.i18n.locales} has catalog entries to read
     * (TQL-YAML-1103), translation gaps against the default locale surface per catalog
     * (TQL-YAML-1008), and every validation-rule / constraint-mapping message key resolves in
     * the default locale (TQL-FIELD-2005; {@code tql.*} keys resolve through the framework's
     * built-in catalog and are skipped).
     */
    private void lintI18n(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        if (!Files.isDirectory(appHome.resolve("messages"))) {
            return;
        }
        io.tesseraql.yaml.i18n.MessageCatalog catalog;
        try {
            catalog = io.tesseraql.yaml.i18n.MessageCatalog.load(appHome.resolve("messages"));
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding("TQL-YAML-1007", "error", "messages", ex.getMessage()));
            return;
        }
        AppConfig config = manifest.config();
        String defaultTag = java.util.Locale.forLanguageTag(
                config.getString("tesseraql.i18n.defaultLocale").orElse("en")).toLanguageTag();

        Object declared = config.navigate("tesseraql.i18n.locales");
        if (declared instanceof List<?> tags) {
            for (Object tag : tags) {
                String normalized = java.util.Locale
                        .forLanguageTag(String.valueOf(tag)).toLanguageTag();
                if (!normalized.equals(defaultTag)
                        && catalog.forLocale(normalized).isEmpty()) {
                    findings.add(new LintFinding("TQL-YAML-1103", "warning", "messages",
                            "Declared locale '" + tag + "' has no messages/" + normalized
                                    + ".yml catalog"));
                }
            }
        }

        java.util.Map<String, String> defaults = catalog.forLocale(defaultTag);
        for (String tag : catalog.tags()) {
            if (tag.equals(defaultTag)) {
                continue;
            }
            List<String> missing = defaults.keySet().stream()
                    .filter(key -> catalog.resolve(tag, key) == null)
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                findings.add(new LintFinding("TQL-YAML-1008", "warning", "messages",
                        "Catalog '" + tag + "' is missing " + missing.size()
                                + " key(s) present in the default locale '" + defaultTag
                                + "' (first: " + missing.get(0) + ")"));
            }
        }

        for (RouteFile route : manifest.routes()) {
            String source = appHome.relativize(route.source()).toString().replace('\\', '/');
            route.definition().validate().forEach((id, rule) -> lintMessageKey(catalog,
                    defaultTag, rule.message(), "Validation rule '" + id + "'", source,
                    findings));
            if (route.definition().errors() != null) {
                route.definition().errors().constraints()
                        .forEach((constraint, mapping) -> lintMessageKey(catalog, defaultTag,
                                mapping.message(), "Constraint mapping '" + constraint + "'",
                                source, findings));
            }
        }
    }

    /** Warns when a declared message key has no default-locale text to render. */
    private void lintMessageKey(io.tesseraql.yaml.i18n.MessageCatalog catalog, String defaultTag,
            String key, String owner, String source, List<LintFinding> findings) {
        if (key == null || key.isBlank() || key.startsWith("tql.")) {
            return;
        }
        if (catalog.resolve(defaultTag, key) == null) {
            findings.add(new LintFinding("TQL-FIELD-2005", "warning", source,
                    owner + " declares message key '" + key + "' that no messages/" + defaultTag
                            + ".yml entry resolves"));
        }
    }

    private void lintRoute(Path appHome, AppConfig config, RouteFile route,
            List<LintFinding> findings) {
        RouteDefinition definition = route.definition();
        String source = appHome.relativize(route.source()).toString().replace('\\', '/');

        if (!KNOWN_ROUTE_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1002", "error", source,
                    "Unknown route recipe '" + definition.recipe() + "'"));
        }
        if (definition.sql() != null && !definition.sql().isContract()
                && definition.sql().file() != null) {
            Path sqlFile = route.source().getParent().resolve(definition.sql().file());
            if (!Files.isRegularFile(sqlFile)) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Referenced SQL file is missing: " + definition.sql().file()));
            }
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null
                    && !Files.isRegularFile(route.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        lintOptimisticLocking(route, definition, source, findings);
        lintValidation(route, definition, source, findings);
        lintNotify(config, definition, source, findings);
        lintWebhook(config, definition, source, findings);
        lintPublish(config, definition, source, findings);
        if (definition.consume() != null) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "consume: is only"
                    + " supported on a queue-consume route under consume/, not the '"
                    + definition.recipe() + "' recipe"));
        }
        lintPdfExport(route, definition, source, findings);
        lintEmbeddedVariables(route, definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null
                && !policyDefined(config, definition.security().policy())) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "Route references undefined policy '" + definition.security().policy()
                            + "' (deny by default)"));
        }
        lintTenantPredicate(config, route, definition, source, findings);
    }

    /** A {@code {placeholder}} reference inside an embedded-variable template. */
    private static final Pattern EMBEDDED_PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    /**
     * An embedded variable ({@code /*# … {x} … *}{@code /}) interpolates its placeholder values into
     * the SQL text, not a {@code ?} bind, so a request-controlled value there is an injection vector
     * unless allowlisted. This requires every placeholder that resolves to a request input to be
     * {@code enum}-constrained (the runtime guard against meta-characters is only defense in depth).
     */
    private void lintEmbeddedVariables(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        SqlBinding sql = definition.sql();
        if (sql == null || sql.isContract() || sql.file() == null) {
            return;
        }
        Path sqlFile = route.source().getParent().resolve(sql.file());
        if (!Files.isRegularFile(sqlFile)) {
            return; // missing-file is reported separately
        }
        Set<String> placeholders = new LinkedHashSet<>();
        try {
            collectEmbeddedPlaceholders(Sql2WayParser.parse(Files.readString(sqlFile)),
                    placeholders);
        } catch (Exception ignored) {
            return; // SQL syntax / IO errors surface through other checks
        }
        Map<String, String> params = sql.params() == null ? Map.of() : sql.params();
        Map<String, InputField> inputs = definition.input() == null ? Map.of() : definition.input();
        for (String placeholder : placeholders) {
            int dot = placeholder.indexOf('.');
            String root = dot < 0 ? placeholder : placeholder.substring(0, dot);
            String input = requestInput(params.get(root));
            if (input == null) {
                continue; // not a request input (constant / principal / loop var) — trusted
            }
            InputField field = inputs.get(input);
            if (field == null || field.enumValues() == null || field.enumValues().isEmpty()) {
                findings.add(new LintFinding("TQL-SQL-2109", "error", source,
                        "Embedded variable '{" + placeholder + "}' interpolates request input '"
                                + input + "' into SQL; constrain it with an 'enum' allowlist to "
                                + "prevent injection"));
            }
        }
    }

    /** The input name a {@code sql.params} source binds from a request, or {@code null} otherwise. */
    private static String requestInput(String paramSource) {
        if (paramSource == null) {
            return null;
        }
        for (String prefix : List.of("query.", "params.", "body.")) {
            if (paramSource.startsWith(prefix)) {
                return paramSource.substring(prefix.length());
            }
        }
        return null;
    }

    private static void collectEmbeddedPlaceholders(List<SqlNode> nodes, Set<String> out) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Embedded embedded -> {
                    Matcher matcher = EMBEDDED_PLACEHOLDER.matcher(embedded.template());
                    while (matcher.find()) {
                        out.add(matcher.group(1).trim());
                    }
                }
                case SqlNode.If conditional -> conditional.branches()
                        .forEach(branch -> collectEmbeddedPlaceholders(branch.body(), out));
                case SqlNode.For loop -> collectEmbeddedPlaceholders(loop.body(), out);
                default -> {
                    // Text/Bind/ListBind/Scope hold no embedded placeholders.
                }
            }
        }
    }

    /**
     * Policy ids are dotted names ({@code users.read}): literal keys of the policies map, not
     * nested config paths, so a {@code navigate} walk on the full path never finds them.
     */
    private static boolean policyDefined(AppConfig config, String policy) {
        return config.navigate("tesseraql.security.policies") instanceof java.util.Map<?, ?> map
                && map.containsKey(policy);
    }

    /**
     * In shared-schema tenancy, every tenant-owned query must constrain rows by the tenant or it
     * leaks data across tenants (design ch. 30.4). Warns when an enabled shared-schema app has a
     * SQL route that neither binds {@code tenant.*} nor mentions a tenant column.
     */
    private void lintTenantPredicate(AppConfig config, RouteFile route, RouteDefinition definition,
            String source, List<LintFinding> findings) {
        boolean enabled = config.getString("tenancy.enabled").map(Boolean::parseBoolean)
                .orElse(false);
        String mode = config.getString("tenancy.mode").orElse("shared-schema");
        if (!enabled || !"shared-schema".equals(mode)) {
            return;
        }
        if (definition.sql() == null || definition.sql().isContract()
                || definition.sql().file() == null) {
            return;
        }
        boolean boundToTenant = definition.sql().params().values().stream()
                .anyMatch(expr -> expr != null && expr.startsWith("tenant."));
        if (boundToTenant) {
            return;
        }
        Path sqlFile = route.source().getParent().resolve(definition.sql().file());
        if (Files.isRegularFile(sqlFile) && readQuietly(sqlFile).toLowerCase().contains("tenant")) {
            return;
        }
        findings.add(new LintFinding("TQL-TENANT-3001", "warning", source,
                "Shared-schema route '" + definition.id()
                        + "' has no tenant predicate; bind tenant.id or filter by a tenant column"));
    }

    private static final Pattern SCOPE_DIRECTIVE = Pattern
            .compile("/\\*%\\s*scope\\s+([^*]+?)\\s*\\*/");
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Lints organizational data scoping (roadmap Phase 29): every {@code scope/} definition is
     * well-formed, and every {@code /*%scope%/} directive in a query names a declared scope with a
     * valid {@code on <alias>}.
     */
    private void lintScopes(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        Set<String> declared = new HashSet<>();
        for (ScopeFile scope : manifest.scopes()) {
            lintScopeDefinition(appHome, scope, findings);
            if (scope.definition().id() != null) {
                declared.add(scope.definition().id());
            }
        }
        for (RouteFile route : manifest.routes()) {
            lintScopeDirectives(appHome, route, declared, findings);
        }
        for (RouteFile consumer : manifest.consumers()) {
            lintScopeDirectives(appHome, consumer, declared, findings);
        }
    }

    private void lintAttachments(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            lintAttachmentDefinition(appHome, attachment, findings);
        }
    }

    /**
     * Object-storage egress (roadmap Phase 30 slice 2): when {@code provider: s3}, every attachment's
     * resolved bucket must be in {@code tesseraql.object-storage.allowedBuckets} (deny-by-default,
     * mirroring the HTTP/poll egress allow-lists). The {@code file} provider needs no allow-list.
     */
    private void lintObjectStorageEgress(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        io.tesseraql.yaml.config.AppConfig config = manifest.config();
        String provider = config.getString("tesseraql.object-storage.provider").orElse("file");
        if (!"s3".equalsIgnoreCase(provider)) {
            return;
        }
        Set<String> allowed = new HashSet<>();
        if (config
                .navigate("tesseraql.object-storage.allowedBuckets") instanceof List<?> declared) {
            declared.forEach(value -> allowed.add(String.valueOf(value)));
        }
        for (io.tesseraql.yaml.manifest.AttachmentFile attachment : manifest.attachments()) {
            io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
            String source = relative(appHome, attachment.source());
            String logical = def.bucket();
            if (logical == null || logical.isBlank()) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id()
                        + "' must declare a bucket when tesseraql.object-storage.provider"
                        + " is s3"));
                continue;
            }
            String real = config.getString(
                    "tesseraql.object-storage.buckets." + logical + ".bucket").orElse(logical);
            if (!allowed.contains(real)) {
                findings.add(new LintFinding("TQL-SEC-4110", "error", source, "attachment '"
                        + def.id() + "' targets bucket '" + real + "' which is not in "
                        + "tesseraql.object-storage.allowedBuckets (deny by default)"));
            }
        }
    }

    /** Checks an attachment definition: kind, base path, owning record, and upload limits. */
    private void lintAttachmentDefinition(Path appHome,
            io.tesseraql.yaml.manifest.AttachmentFile attachment, List<LintFinding> findings) {
        String source = relative(appHome, attachment.source());
        io.tesseraql.yaml.model.AttachmentDefinition def = attachment.definition();
        String id = def.id();
        if (!"attachment".equals(def.kind())) {
            findings.add(new LintFinding("TQL-ATTACH-3401", "error", source,
                    "attachment '" + id + "' must declare kind: attachment"));
        }
        boolean hasBasePath = def.basePath() != null && !def.basePath().isBlank();
        if (!hasBasePath) {
            findings.add(new LintFinding("TQL-ATTACH-3402", "error", source,
                    "attachment '" + id + "' must declare a basePath"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.RecordSpec record = def.record();
        boolean hasEntity = record != null && record.entity() != null
                && !record.entity().isBlank();
        boolean hasKey = record != null && record.key() != null && !record.key().isBlank();
        if (!hasEntity || !hasKey) {
            findings.add(new LintFinding("TQL-ATTACH-3403", "error", source,
                    "attachment '" + id + "' must declare record.entity and record.key"));
        } else if (hasBasePath && !def.basePath().contains("{" + record.key() + "}")) {
            findings.add(new LintFinding("TQL-ATTACH-3404", "error", source,
                    "attachment '" + id + "' basePath must contain the record key '{"
                            + record.key() + "}' as a path parameter"));
        }
        io.tesseraql.yaml.model.AttachmentDefinition.Limits limits = def.limits();
        if (limits == null || limits.maxBytesValue() <= 0) {
            findings.add(new LintFinding("TQL-ATTACH-3405", "error", source,
                    "attachment '" + id + "' must declare a positive limits.maxBytes (e.g. 25MB)"));
        }
    }

    /** Checks a scope definition: each arm declares exactly one effect, a valid when, a real file. */
    private void lintScopeDefinition(Path appHome, ScopeFile scope, List<LintFinding> findings) {
        String source = relative(appHome, scope.source());
        ScopeDefinition definition = scope.definition();
        if (!"scope".equals(definition.kind())) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    "scope '" + definition.id() + "' must declare kind: scope"));
        }
        if (definition.match().isEmpty()) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    "scope '" + definition.id() + "' declares no match arms"));
        }
        Path scopeDir = scope.source().getParent();
        int index = 0;
        for (MatchArm arm : definition.match()) {
            String where = "scope '" + definition.id() + "' arm " + index;
            boolean hasApply = arm.apply() != null && !arm.apply().isBlank();
            boolean hasFile = arm.file() != null && !arm.file().isBlank();
            if (hasApply == hasFile) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " must declare exactly one of apply (all|none) or file"));
            }
            if (hasApply && !arm.isAll() && !arm.isNone()) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " apply must be 'all' or 'none', not '" + arm.apply() + "'"));
            }
            if (hasFile && !Files.isRegularFile(scopeDir.resolve(arm.file()))) {
                findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                        where + " references missing fragment '" + arm.file() + "'"));
            }
            lintWhen(arm.when(), where, source, findings);
            index++;
        }
    }

    private void lintWhen(WhenCondition when, String where, String source,
            List<LintFinding> findings) {
        if (when == null) {
            return;
        }
        int set = (when.role() != null ? 1 : 0) + (when.permission() != null ? 1 : 0)
                + (when.claim() != null ? 1 : 0);
        if (set > 1) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when must set only one of role/permission/claim"));
        }
        if (when.claim() != null && when.value() == null) {
            findings.add(new LintFinding("TQL-SCOPE-3012", "error", source,
                    where + " when claim needs an 'equals' value"));
        }
    }

    /** Checks each {@code /*%scope%/} directive in a route's SQL names a declared scope. */
    private void lintScopeDirectives(Path appHome, RouteFile route, Set<String> declared,
            List<LintFinding> findings) {
        String source = relative(appHome, route.source());
        String id = route.definition().id();
        for (Path sqlFile : routeSqlFiles(route)) {
            if (!Files.isRegularFile(sqlFile)) {
                continue;
            }
            Matcher matcher = SCOPE_DIRECTIVE.matcher(readQuietly(sqlFile));
            while (matcher.find()) {
                String content = stripAsBoolean(matcher.group(1).trim());
                String name = content;
                String alias = null;
                int on = content.indexOf(" on ");
                if (on >= 0) {
                    name = content.substring(0, on).trim();
                    alias = content.substring(on + " on ".length()).trim();
                }
                if (!declared.contains(name)) {
                    findings.add(new LintFinding("TQL-SCOPE-3011", "error", source,
                            "route '" + id + "' references scope '" + name
                                    + "' not declared under scope/"));
                }
                if (alias != null && !SQL_IDENTIFIER.matcher(alias).matches()) {
                    findings.add(new LintFinding("TQL-SCOPE-3013", "error", source,
                            "route '" + id + "' scope 'on' alias '" + alias
                                    + "' is not a SQL identifier"));
                }
            }
        }
    }

    /** The roots a transition guard may reference (roadmap Phase 28); {@code task} arrives in slice 2. */
    private static final Set<String> GUARD_ROOTS = Set.of("document", "task", "principal");

    /**
     * Lints approval workflows (roadmap Phase 28): each workflow's states and transitions are
     * well-formed (no undeclared/unreachable states, no dead ends), guards are valid whitelist
     * expressions over the allowed roots, referenced files exist, and the declared mode matches the
     * document fields it needs.
     */
    private void lintWorkflows(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (WorkflowFile workflow : manifest.workflows()) {
            lintWorkflow(appHome, manifest.config(), workflow, findings);
        }
    }

    private void lintWorkflow(Path appHome, AppConfig config, WorkflowFile workflow,
            List<LintFinding> findings) {
        String source = relative(appHome, workflow.source());
        WorkflowDefinition def = workflow.definition();
        String id = def.id();
        Path dir = workflow.source().getParent();

        Set<String> states = new LinkedHashSet<>();
        int initialMarked = 0;
        for (StateSpec state : def.states()) {
            if (state.id() != null) {
                states.add(state.id());
            }
            if (state.isInitial()) {
                initialMarked++;
            }
        }
        if (def.initial() != null && !states.contains(def.initial())) {
            findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source, "workflow '" + id
                    + "' initial state '" + def.initial() + "' is not declared in states"));
        }
        if (initialMarked > 1) {
            findings.add(new LintFinding("TQL-WORKFLOW-3102", "error", source,
                    "workflow '" + id + "' declares more than one initial state"));
        }

        Set<String> transitionIds = new LinkedHashSet<>();
        Map<String, String> transitionFrom = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (TransitionSpec t : def.transitions()) {
            if (t.id() != null) {
                transitionIds.add(t.id());
                transitionFrom.put(t.id(), t.from());
            }
            String where = "workflow '" + id + "' transition '" + t.id() + "'";
            if (t.from() == null || !states.contains(t.from())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " from-state '" + t.from() + "' is not declared in states"));
            } else {
                outgoing.computeIfAbsent(t.from(), k -> new ArrayList<>()).add(t.to());
            }
            if (t.to() == null || !states.contains(t.to())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " to-state '" + t.to() + "' is not declared in states"));
            }
            lintGuard(t.guard(), where, source, findings);
            if (t.command() != null && !Files.isRegularFile(dir.resolve(t.command()))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " references missing command '" + t.command() + "'"));
            }
            if (t.assign() != null && t.assign().file() != null
                    && !Files.isRegularFile(dir.resolve(t.assign().file()))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                        where + " references missing assignee file '" + t.assign().file() + "'"));
            }
        }

        if (def.initial() != null && states.contains(def.initial())) {
            Set<String> reachable = reachableStates(def.initial(), outgoing);
            for (StateSpec state : def.states()) {
                if (state.id() != null && !reachable.contains(state.id())) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3102", "error", source,
                            "workflow '" + id + "' state '" + state.id()
                                    + "' is unreachable from the initial state"));
                }
            }
        }
        for (StateSpec state : def.states()) {
            boolean hasOutgoing = outgoing.containsKey(state.id());
            if (state.isTerminal() && hasOutgoing) {
                findings.add(new LintFinding("TQL-WORKFLOW-3105", "warning", source,
                        "workflow '" + id + "' terminal state '" + state.id()
                                + "' has an outgoing transition"));
            }
            if (!state.isTerminal() && !hasOutgoing) {
                findings.add(new LintFinding("TQL-WORKFLOW-3105", "warning", source,
                        "workflow '" + id + "' non-terminal state '" + state.id()
                                + "' has no outgoing transition (dead end)"));
            }
        }

        for (DeadlineSpec deadline : def.deadlines()) {
            String where = "workflow '" + id + "' deadline on '" + deadline.state() + "'";
            if (deadline.state() != null && !states.contains(deadline.state())) {
                findings.add(new LintFinding("TQL-WORKFLOW-3101", "error", source,
                        where + " names a state not declared in states"));
            }
            DeadlineSpec.OnBreachSpec onBreach = deadline.onBreach();
            if (onBreach != null) {
                if (onBreach.escalate() != null && !onBreach.escalate().isBlank()) {
                    if (!transitionIds.contains(onBreach.escalate())) {
                        findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' is not a declared transition"));
                    } else if (!java.util.Objects.equals(transitionFrom.get(onBreach.escalate()),
                            deadline.state())) {
                        // The sweeper auto-fires it from the deadline's state, so it could never
                        // advance from a different from-state.
                        findings.add(new LintFinding("TQL-WORKFLOW-3107", "error", source,
                                where + " escalate '" + onBreach.escalate()
                                        + "' starts from '"
                                        + transitionFrom.get(onBreach.escalate())
                                        + "', not the deadline's state"));
                    }
                }
                if (onBreach.reassign() != null
                        && !Files.isRegularFile(dir.resolve(onBreach.reassign()))) {
                    findings.add(new LintFinding("TQL-WORKFLOW-3104", "error", source, where
                            + " references missing reassign file '" + onBreach.reassign() + "'"));
                }
            }
        }

        lintWorkflowMode(def, config, source, findings);
    }

    /** Parses a guard and checks every path it reads is rooted at an allowed variable. */
    private void lintGuard(String guard, String where, String source, List<LintFinding> findings) {
        if (guard == null || guard.isBlank()) {
            return;
        }
        Expr expr;
        try {
            expr = ExpressionParser.parse(guard);
        } catch (RuntimeException ex) {
            findings.add(new LintFinding("TQL-WORKFLOW-3103", "error", source,
                    where + " guard is not a valid expression: " + ex.getMessage()));
            return;
        }
        List<List<String>> paths = new ArrayList<>();
        collectGuardPaths(expr, paths);
        for (List<String> path : paths) {
            if (!path.isEmpty() && !GUARD_ROOTS.contains(path.get(0))) {
                findings.add(new LintFinding("TQL-WORKFLOW-3103", "error", source,
                        where + " guard references '" + String.join(".", path)
                                + "'; allowed roots are document, task, principal"));
            }
        }
    }

    private static void collectGuardPaths(Expr expr, List<List<String>> out) {
        if (expr instanceof Expr.Path p) {
            out.add(p.segments());
        } else if (expr instanceof Expr.Not n) {
            collectGuardPaths(n.operand(), out);
        } else if (expr instanceof Expr.Logical l) {
            collectGuardPaths(l.left(), out);
            collectGuardPaths(l.right(), out);
        } else if (expr instanceof Expr.Comparison c) {
            collectGuardPaths(c.left(), out);
            collectGuardPaths(c.right(), out);
        }
    }

    private static Set<String> reachableStates(String start, Map<String, List<String>> outgoing) {
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        reachable.add(start);
        while (!queue.isEmpty()) {
            String state = queue.poll();
            for (String next : outgoing.getOrDefault(state, List.of())) {
                if (next != null && reachable.add(next)) {
                    queue.add(next);
                }
            }
        }
        return reachable;
    }

    /** Checks the document fields the declared mode requires are present (roadmap Phase 28). */
    private void lintWorkflowMode(WorkflowDefinition def, AppConfig config, String source,
            List<LintFinding> findings) {
        String mode = def.mode();
        if (mode == null || mode.isBlank()) {
            mode = config.getString("tesseraql.workflow.mode").orElse("app");
        }
        boolean managed = "managed".equalsIgnoreCase(mode);
        WorkflowDefinition.DocumentSpec doc = def.document();
        if (doc == null) {
            findings.add(new LintFinding("TQL-WORKFLOW-3106", "error", source,
                    "workflow '" + def.id() + "' declares no document"));
            return;
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(doc.table())) {
            missing.add("document.table");
        }
        if (isBlank(doc.key())) {
            missing.add("document.key");
        }
        if (managed) {
            if (isBlank(doc.type())) {
                missing.add("document.type");
            }
        } else if (isBlank(doc.stateColumn())) {
            missing.add("document.stateColumn");
        }
        if (!missing.isEmpty()) {
            findings.add(new LintFinding("TQL-WORKFLOW-3106", "error", source,
                    "workflow '" + def.id() + "' in " + (managed ? "managed" : "app")
                            + " mode requires " + String.join(", ", missing)));
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The non-contract SQL files a route references (its {@code sql}, {@code steps}, {@code queries}). */
    private static List<Path> routeSqlFiles(RouteFile route) {
        RouteDefinition definition = route.definition();
        Path dir = route.source().getParent();
        Map<String, SqlBinding> bindings = new LinkedHashMap<>();
        if (definition.sql() != null) {
            bindings.put("sql", definition.sql());
        }
        bindings.putAll(definition.steps());
        bindings.putAll(definition.queries());
        List<Path> files = new ArrayList<>();
        for (SqlBinding binding : bindings.values()) {
            if (binding != null && !binding.isContract() && binding.file() != null) {
                files.add(dir.resolve(binding.file()));
            }
        }
        return files;
    }

    private static String relative(Path appHome, Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /** Drops the {@code as boolean} suffix so the scope name/alias parse the same as a predicate. */
    static String stripAsBoolean(String content) {
        return content.endsWith(" as boolean")
                ? content.substring(0, content.length() - " as boolean".length()).trim()
                : content;
    }

    /**
     * Nudges version-column predicates on command UPDATEs (roadmap Phase 18): a row-count
     * expectation without a version predicate only detects "row vanished", not concurrent
     * edits; a version predicate without an expectation silently affects zero rows.
     */
    private void lintOptimisticLocking(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (!"command-json".equals(definition.recipe())) {
            return;
        }
        java.util.Map<String, io.tesseraql.yaml.model.SqlBinding> bindings = new java.util.LinkedHashMap<>(
                definition.steps());
        if (definition.sql() != null) {
            bindings.put("sql", definition.sql());
        }
        bindings.forEach((name, binding) -> {
            if (binding.file() == null) {
                return;
            }
            Path file = route.source().getParent().resolve(binding.file());
            if (!Files.isRegularFile(file)) {
                return;
            }
            String sql = readQuietly(file).toLowerCase();
            boolean isUpdate = sql.stripLeading().startsWith("update");
            boolean versionPredicate = sql.contains("version");
            if (isUpdate && binding.expect() != null && !versionPredicate) {
                findings.add(new LintFinding("TQL-SQL-2104", "warning", source,
                        "Step '" + name + "': UPDATE declares expect.rows but has no"
                                + " version-column predicate; a concurrent edit is only detected"
                                + " when the row vanishes - add `and version = ...`"));
            }
            if (isUpdate && binding.expect() == null && versionPredicate) {
                findings.add(new LintFinding("TQL-SQL-2105", "warning", source,
                        "Step '" + name + "': UPDATE has a version predicate but no expect.rows;"
                                + " a stale edit silently affects zero rows - declare"
                                + " expect: { rows: 1 }"));
            }
        });
    }

    /**
     * Statically checks the {@code validate:} block (roadmap Phase 19), reporting at lint time
     * what would otherwise fail at route build time: validation only applies to command routes,
     * a rule declares exactly one of {@code rule:}/{@code file:} plus a {@code field:}, its
     * expressions parse, its SQL file exists, and that SQL is a SELECT (it runs inside the
     * command's transaction and must not write).
     */
    private void lintValidation(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.validate().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1003", "error", source,
                    "validate: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        definition.validate().forEach((id, rule) -> {
            if (rule.isExpression() == rule.isSql()) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id
                                + "' must declare exactly one of rule: or file:"));
                return;
            }
            if (rule.field() == null || rule.field().isBlank()) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id + "' needs a field: to report violations"
                                + " against"));
            }
            lintRuleExpression(id, rule.when(), source, findings);
            if (rule.isExpression()) {
                lintRuleExpression(id, rule.rule(), source, findings);
                return;
            }
            Path file = route.source().getParent().resolve(rule.file());
            if (!Files.isRegularFile(file)) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Validation rule '" + id + "' references a missing SQL file: "
                                + rule.file()));
            } else if (!io.tesseraql.core.validation.ValidationRules
                    .isSelect(readQuietly(file))) {
                findings.add(new LintFinding("TQL-FIELD-2003", "error", source,
                        "Validation rule '" + id + "': validation SQL must be a SELECT"
                                + " returning violations - it must not write"));
            }
        });
    }

    /**
     * Statically checks the inbound {@code webhook} recipe (roadmap Phase 26): the route names a
     * verifier ({@code TQL-SEC-4082}) that is configured under
     * {@code tesseraql.connectors.webhooks} ({@code TQL-SEC-4083}, so a webhook is never served
     * unverified), and runs a SQL pipeline ({@code TQL-YAML-1008}). A {@code webhook:} block on a
     * non-webhook recipe is a misuse.
     */
    private void lintWebhook(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (!"webhook".equals(definition.recipe())) {
            if (definition.webhook() != null) {
                findings.add(new LintFinding("TQL-YAML-1008", "error", source,
                        "webhook: is only supported on the webhook recipe, not '"
                                + definition.recipe() + "'"));
            }
            return;
        }
        String provider = definition.webhook() == null ? null : definition.webhook().provider();
        if (provider == null || provider.isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4082", "error", source,
                    "webhook route '" + definition.id() + "' needs a webhook.provider"));
        } else if (config.navigate("tesseraql.connectors.webhooks." + provider) == null) {
            findings.add(new LintFinding("TQL-SEC-4083", "error", source, "webhook route '"
                    + definition.id() + "' references verifier '" + provider
                    + "' not configured under tesseraql.connectors.webhooks"));
        }
        if (definition.sql() == null && definition.steps().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1008", "error", source, "webhook route '"
                    + definition.id() + "' needs a sql: or steps: pipeline"));
        }
    }

    /**
     * Statically checks a {@code publish:} block (roadmap Phase 27): it rides a transactional
     * command ({@code TQL-YAML-1010}) and names a channel configured under
     * {@code tesseraql.messaging.channels} ({@code TQL-SEC-4091}), so a publish never targets a
     * channel that does not exist.
     */
    private void lintPublish(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var publish = definition.publish();
        if (publish == null) {
            return;
        }
        if (!PUBLISH_RECIPES.contains(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "publish: is only"
                    + " supported on command routes (command-json, webhook, queue-consume), not '"
                    + definition.recipe() + "'"));
            return;
        }
        if (publish.channel() == null || publish.channel().isBlank()) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source,
                    "publish: of '" + definition.id() + "' needs a channel"));
        } else if (config.navigate("tesseraql.messaging.channels." + publish.channel()) == null) {
            findings.add(new LintFinding("TQL-SEC-4091", "error", source, "publish: of '"
                    + definition.id() + "' references channel '" + publish.channel()
                    + "' not configured under tesseraql.messaging.channels"));
        }
    }

    /**
     * Statically checks a {@code queue-consume} route under {@code consume/} (roadmap Phase 27): it
     * uses the {@code queue-consume} recipe, names a channel/topic ({@code TQL-YAML-1009}) whose
     * channel is configured ({@code TQL-SEC-4090}, so a consumer is never wired to a channel that
     * does not exist), and runs a SQL pipeline. Its {@code publish:} and {@code notify:} blocks are
     * linted the same way a command route's are.
     */
    private void lintConsumer(Path appHome, AppConfig config, RouteFile consumer,
            List<LintFinding> findings) {
        RouteDefinition definition = consumer.definition();
        String source = appHome.relativize(consumer.source()).toString().replace('\\', '/');
        if (!"queue-consume".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1010", "error", source, "a consume/ route must"
                    + " use the queue-consume recipe, not '" + definition.recipe() + "'"));
            return;
        }
        var consume = definition.consume();
        if (consume == null || consume.channel() == null || consume.channel().isBlank()
                || consume.topic() == null || consume.topic().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1009", "error", source, "queue-consume route '"
                    + definition.id() + "' needs consume.channel and consume.topic"));
        } else if (config.navigate("tesseraql.messaging.channels." + consume.channel()) == null) {
            findings.add(new LintFinding("TQL-SEC-4090", "error", source, "queue-consume route '"
                    + definition.id() + "' references channel '" + consume.channel()
                    + "' not configured under tesseraql.messaging.channels"));
        }
        if (definition.sql() == null && definition.steps().isEmpty()) {
            findings.add(new LintFinding("TQL-YAML-1009", "error", source, "queue-consume route '"
                    + definition.id() + "' needs a sql: or steps: pipeline"));
        }
        if (definition.sql() != null && definition.sql().file() != null && !Files.isRegularFile(
                consumer.source().getParent().resolve(definition.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + definition.sql().file()));
        }
        definition.steps().forEach((name, step) -> {
            if (step.file() != null && !Files.isRegularFile(
                    consumer.source().getParent().resolve(step.file()))) {
                findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                        "Step '" + name + "' references a missing SQL file: " + step.file()));
            }
        });
        lintPublish(config, definition, source, findings);
        lintNotify(config, definition, source, findings);
    }

    /**
     * Statically checks the {@code notify:} block of a command route (roadmap Phase 20):
     * notifications only apply to command routes, each declares a {@code channel:} that the
     * config knows, and its {@code when:} guard parses.
     */
    private void lintNotify(AppConfig config, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        if (definition.notifications().isEmpty()) {
            return;
        }
        if (!"command-json".equals(definition.recipe())) {
            findings.add(new LintFinding("TQL-YAML-1004", "error", source,
                    "notify: is only supported on command-json routes, not '"
                            + definition.recipe() + "'"));
        }
        definition.notifications()
                .forEach((id, spec) -> lintNotifySpec(config, id, spec, source, findings));
    }

    /**
     * Statically checks a batch job's pipeline steps (roadmap Phase 20, 26): a step declares
     * exactly one of {@code sql:}, {@code notify:}, or {@code http-call:}; notify steps lint like a
     * route's, and http-call steps lint their egress against the allow-list (deny by default).
     */
    private void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        if (job.definition().trigger() != null && job.definition().trigger().poll() != null) {
            lintPollJob(config, job, source, findings);
        }
        for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
            int declared = 0;
            if (step.sql() != null) {
                declared++;
            }
            if (step.notification() != null) {
                declared++;
            }
            if (step.httpCall() != null) {
                declared++;
            }
            if (declared != 1) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source, "Step '"
                        + step.id()
                        + "' must declare exactly one of sql:, notify:, or http-call:"));
                continue;
            }
            if (step.notification() != null) {
                lintNotifySpec(config, step.id(), step.notification(), source, findings);
            } else if (step.httpCall() != null) {
                lintHttpCall(config, step.id(), step.httpCall(), source, findings);
            }
        }
    }

    /**
     * Statically checks a {@code poll:}-triggered file-import job (roadmap Phase 26): the source is
     * a known kind with a path, a remote source has an allow-listed host
     * ({@code TQL-SEC-4080}, deny by default) and a configured credential ({@code TQL-SEC-4081}, a
     * warning), and the job carries an {@code import:} block whose per-row SQL file exists.
     */
    private void lintPollJob(AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.PollSpec poll = job.definition().trigger().poll();
        if (job.definition().trigger().schedule() != null) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Job '" + job.definition().id()
                            + "' declares both a schedule and a poll trigger; declare one"));
        }
        String kind = poll.effectiveSource();
        if (!List.of("local", "sftp", "ftps").contains(kind)) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger source must be local, sftp, or ftps (was '" + poll.source()
                            + "')"));
        }
        if (poll.path() == null || poll.path().isBlank()) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "Poll trigger needs a path: (the directory to poll)"));
        }
        if (poll.isRemote()) {
            if (poll.host() == null || poll.host().isBlank()) {
                findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                        "Poll trigger source '" + kind + "' needs a host:"));
            } else {
                List<String> allowedHosts = new java.util.ArrayList<>();
                if (config
                        .navigate("tesseraql.connectors.poll.allowedHosts") instanceof List<?> h) {
                    h.forEach(value -> allowedHosts.add(String.valueOf(value)));
                }
                if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, poll.host())) {
                    findings.add(new LintFinding("TQL-SEC-4080", "error", source,
                            "Poll trigger targets host '" + poll.host() + "' which is not in"
                                    + " tesseraql.connectors.poll.allowedHosts (deny by default)"));
                }
            }
            if (poll.credential() != null && !poll.credential().isBlank()
                    && config.navigate(
                            "tesseraql.connectors.poll.credentials." + poll.credential()) == null) {
                findings.add(new LintFinding("TQL-SEC-4081", "warning", source,
                        "Poll trigger references undeclared credential '" + poll.credential()
                                + "'"));
            }
        }
        io.tesseraql.yaml.model.ImportSpec importSpec = job.definition().fileImport();
        if (importSpec == null || importSpec.sql() == null || importSpec.sql().file() == null) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source, "Poll-triggered job '"
                    + job.definition().id() + "' needs an import: block with a per-row sql.file"));
        } else if (!Files.isRegularFile(
                job.source().getParent().resolve(importSpec.sql().file()))) {
            findings.add(new LintFinding("TQL-SQL-2103", "error", source,
                    "Referenced SQL file is missing: " + importSpec.sql().file()));
        }
    }

    /**
     * Statically checks an {@code http-call} step's egress (roadmap Phase 26): the target host
     * must resolve to an allow-listed host ({@code TQL-SEC-4070}, deny by default), the url must be
     * an absolute http/https URL ({@code TQL-SEC-4071}), and a referenced credential should be
     * configured ({@code TQL-SEC-4072}, a warning since another environment may declare it). A url
     * carrying an unresolved {@code ${...}} secret in its host cannot be checked statically and is
     * left to the runtime's identical deny-by-default guard.
     */
    private void lintHttpCall(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        String resolved = null;
        if (spec.url() != null && !spec.url().isBlank()) {
            try {
                resolved = config.resolve(spec.url());
            } catch (RuntimeException ex) {
                resolved = spec.url();
            }
        }
        String host = null;
        String scheme = null;
        if (resolved != null) {
            try {
                java.net.URI uri = java.net.URI.create(resolved);
                host = uri.getHost();
                scheme = uri.getScheme();
            } catch (RuntimeException ex) {
                host = null;
            }
        }
        boolean absoluteHttp = host != null && scheme != null
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        if (!absoluteHttp) {
            // Flag a genuinely missing or relative url, but not one we merely cannot resolve yet
            // (an unresolved ${...} secret in the host is checked by the runtime instead).
            if (resolved == null || !resolved.contains("${")) {
                findings.add(new LintFinding("TQL-SEC-4071", "error", source,
                        "http-call step '" + id + "' needs an absolute http or https url:"));
            }
            lintHttpCredential(config, id, spec, source, findings);
            return;
        }
        List<String> allowedHosts = new java.util.ArrayList<>();
        if (config.navigate("tesseraql.http.outbound.allowedHosts") instanceof List<?> declared) {
            declared.forEach(value -> allowedHosts.add(String.valueOf(value)));
        }
        if (!io.tesseraql.yaml.http.HttpOutbound.hostAllowed(allowedHosts, host)) {
            findings.add(new LintFinding("TQL-SEC-4070", "error", source, "http-call step '" + id
                    + "' targets host '" + host + "' which is not in"
                    + " tesseraql.http.outbound.allowedHosts (deny by default)"));
        }
        lintHttpCredential(config, id, spec, source, findings);
    }

    private void lintHttpCredential(AppConfig config, String id,
            io.tesseraql.yaml.model.HttpCallSpec spec, String source, List<LintFinding> findings) {
        String credential = spec.credential();
        if (credential == null || credential.isBlank()) {
            return;
        }
        if (config.navigate("tesseraql.http.outbound.credentials." + credential) == null) {
            findings.add(new LintFinding("TQL-SEC-4072", "warning", source, "http-call step '" + id
                    + "' references undeclared credential '" + credential + "'"));
        }
    }

    private void lintNotifySpec(AppConfig config, String id,
            io.tesseraql.yaml.model.NotifySpec spec, String source, List<LintFinding> findings) {
        if (spec.channel() == null || spec.channel().isBlank()) {
            findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                    "Notification '" + id + "' needs a channel:"));
        } else if (config
                .navigate("tesseraql.notifications.channels." + spec.channel()) == null) {
            // A warning, not an error: another environment's config may declare the channel.
            findings.add(new LintFinding("TQL-YAML-1102", "warning", source,
                    "Notification '" + id + "' references undeclared channel '"
                            + spec.channel() + "'"));
        }
        if (spec.when() != null && !spec.when().isBlank()) {
            try {
                io.tesseraql.core.expr.ExpressionParser.parse(spec.when());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-SQL-2101", "error", source,
                        "Notification '" + id + "' has a malformed when: expression: "
                                + ex.getMessage()));
            }
        }
    }

    /**
     * Statically checks a printable-document export (roadmap Phase 21): {@code format: pdf} is a
     * print format, so the workbook-only options ({@code sheet:}, {@code startCell:}) do not
     * apply, and the template - rendered through the standard template engine - must be an
     * {@code .html} file colocated with the route.
     */
    private void lintPdfExport(RouteFile route, RouteDefinition definition, String source,
            List<LintFinding> findings) {
        io.tesseraql.yaml.model.ExportSpec spec = definition.fileExport();
        if (spec == null || !"pdf".equals(spec.format())) {
            return;
        }
        if (spec.sheet() != null || spec.startCell() != null) {
            findings.add(new LintFinding("TQL-YAML-1005", "error", source,
                    "pdf export: sheet:/startCell: are workbook options - a pdf lays out"
                            + " through its template, not cell placement"));
        }
        if (spec.template() == null) {
            return;
        }
        if (!spec.template().endsWith(".html")) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "pdf export template '" + spec.template()
                            + "' must be an .html file (it renders through the template"
                            + " engine before PDF conversion)"));
        } else if (!Files.isRegularFile(route.source().getParent().resolve(spec.template()))) {
            findings.add(new LintFinding("TQL-YAML-1006", "error", source,
                    "pdf export references a missing template: " + spec.template()));
        }
    }

    private void lintRuleExpression(String ruleId, String expression, String source,
            List<LintFinding> findings) {
        if (expression == null || expression.isBlank()) {
            return;
        }
        try {
            io.tesseraql.core.expr.ExpressionParser.parse(expression);
        } catch (RuntimeException ex) {
            findings.add(new LintFinding("TQL-SQL-2101", "error", source,
                    "Validation rule '" + ruleId + "' has a malformed expression: "
                            + ex.getMessage()));
        }
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file);
        } catch (java.io.IOException ex) {
            return "";
        }
    }
}
