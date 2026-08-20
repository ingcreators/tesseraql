package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * API-key configuration and the clients that store key hashes.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ApiKeyConfigRules implements LintRule {

    private static final String API_KEY_AUTH_UNCONFIGURED = "TQL-SEC-4044";

    private static final String API_KEY_CLIENT_WITHOUT_SECRET_HASH = "TQL-SEC-4045";

    private static final String API_KEY_CLIENT_WITHOUT_GRANTS = "TQL-SEC-4046";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintApiKeyConfig(context.appHome(), manifest, manifest.config(), findings);
    }

    void lintApiKeyConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        boolean apiKeysConfigured = config.navigate("tesseraql.security.apiKeys") != null;
        if (!apiKeysConfigured) {
            // Every route-shaped surface, not just routes: a queue consumer and an MCP tool
            // declare security: the same way, and this check reached neither.
            for (Map.Entry<Path, RouteDefinition> document : LintSupport
                    .authoringDocuments(manifest)) {
                io.tesseraql.yaml.model.SecuritySpec security = document.getValue().security();
                if (security != null && "api-key".equals(security.auth())) {
                    String source = appHome.relativize(document.getKey()).toString()
                            .replace('\\', '/');
                    findings.add(new LintFinding(API_KEY_AUTH_UNCONFIGURED, ERROR, source,
                            "'" + document.getValue().id() + "' declares auth: api-key but no"
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
                findings.add(new LintFinding(API_KEY_CLIENT_WITHOUT_SECRET_HASH, ERROR, "config",
                        "API-key client '" + id + "' must declare a secretHash; raw keys are never"
                                + " stored"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding(API_KEY_CLIENT_WITHOUT_GRANTS, WARNING, "config",
                        "API-key client '" + id + "' grants no roles or permissions; service"
                                + " callers should be least-privilege"));
            }
        });
    }
}
