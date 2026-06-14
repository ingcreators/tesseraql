package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Statically lints an app home, independent of Maven, so it is unit-testable (design ch. 18, 20).
 *
 * <p>The first rule set checks recipes are known, referenced SQL files exist, and route security
 * policies are defined (deny-by-default safety). More rules (large-data, tenant predicate, field
 * authorization) are added alongside their features.
 */
public final class AppLinter {

    private static final Set<String> KNOWN_ROUTE_RECIPES = Set.of("query-json", "command-json",
            "query-html", "page", "query-export", "file-import", "file-export");
    /** Recipes an application-declared MCP tool may use (roadmap Phase 24 follow-on). */
    private static final Set<String> KNOWN_TOOL_RECIPES = Set.of("query-json", "command-json");
    /** Recipes an MCP Apps UI resource may use - both render HTML (roadmap Phase 24). */
    private static final Set<String> KNOWN_UI_RECIPES = Set.of("query-html", "page");
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
        lintDuplicateResourceUris(appHome, manifest, findings);
        lintToolUiLinks(appHome, manifest, findings);
        lintI18n(appHome, manifest, findings);
        lintSecurityConfig(appHome, manifest, findings);
        return findings;
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
        lintPdfExport(route, definition, source, findings);
        if (definition.security() != null && definition.security().policy() != null
                && !policyDefined(config, definition.security().policy())) {
            findings.add(new LintFinding("TQL-SEC-4030", "warning", source,
                    "Route references undefined policy '" + definition.security().policy()
                            + "' (deny by default)"));
        }
        lintTenantPredicate(config, route, definition, source, findings);
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
     * Statically checks a batch job's pipeline steps (roadmap Phase 20): a step declares
     * exactly one of {@code sql:} or {@code notify:}, and notify steps lint like a route's.
     */
    private void lintJob(Path appHome, AppConfig config, io.tesseraql.yaml.manifest.JobFile job,
            List<LintFinding> findings) {
        String source = appHome.relativize(job.source()).toString().replace('\\', '/');
        for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
            if ((step.sql() == null) == (step.notification() == null)) {
                findings.add(new LintFinding("TQL-FIELD-2004", "error", source,
                        "Step '" + step.id() + "' must declare exactly one of sql: or notify:"));
                continue;
            }
            if (step.notification() != null) {
                lintNotifySpec(config, step.id(), step.notification(), source, findings);
            }
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
