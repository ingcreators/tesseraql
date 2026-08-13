package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * Two MCP resources claiming one uri.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class DuplicateResourceUriRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintDuplicateResourceUris(context.appHome(), manifest, findings);
    }

    /**
     * Two resources sharing a {@code uri} would collide at startup (the MCP server keys every
     * resource by its uri and rejects a duplicate), so flag it at lint time instead - deny by
     * default, fail fast. UI resources ({@code ui://}) share that single namespace with plain
     * resources, so they are checked together.
     */
    void lintDuplicateResourceUris(Path appHome, AppManifest manifest,
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
}
