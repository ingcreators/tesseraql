package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * A tool's link to the UI resource that renders it.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ToolUiLinkRules implements LintRule {

    private static final String TOOL_UI_LINK_UNRESOLVED = "TQL-MCP-1012";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintToolUiLinks(context.appHome(), manifest, findings);
    }

    /**
     * A tool's {@code ui:} link must resolve to a UI resource the app declares; a dangling link
     * would advertise a {@code _meta.ui.resourceUri} no {@code resources/read} can serve. Fail fast
     * at lint time rather than at render.
     */
    void lintToolUiLinks(Path appHome, AppManifest manifest, List<LintFinding> findings) {
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
                findings.add(new LintFinding(TOOL_UI_LINK_UNRESOLVED, ERROR, source,
                        "MCP tool '" + tool.definition().id() + "' links ui: '" + link
                                + "' but no kind: ui resource declares that uri"));
            }
        }
    }
}
