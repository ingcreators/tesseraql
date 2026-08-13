package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * Two MCP documents claiming one name.
 *
 * <p>Every MCP primitive lives in a single flat namespace the client sees: a tool is addressed
 * by its {@code id}, a prompt by its {@code id}, a resource by its {@code uri}. The
 * {@code mcp/} tree's folders organize the files and name nothing (docs/app-mcp.md), so two
 * documents in different folders can claim the same name — which is exactly what folders make
 * easy and a flat tree made obvious.
 *
 * <p>Each collision is refused at startup, but only by the primitive registry's own
 * {@code IllegalArgumentException}, which names the id and not the file that declared it — and
 * a duplicate tool id gets there through two compiled routes sharing one route id first. So it
 * is flagged here, where the author is still looking and both files can be named.
 */
final class DuplicateMcpNameRules implements LintRule {

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintDuplicateResourceUris(context.appHome(), manifest, findings);
        lintDuplicateToolAndPromptIds(context.appHome(), manifest, findings);
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

    /**
     * Tools and prompts are named by their {@code id}, each in its own namespace — a tool and a
     * prompt may share a name, two tools may not. A duplicate tool id also compiles to two
     * routes with one route id, so the first failure an author sees today is a Camel startup
     * error rather than either file's name.
     */
    void lintDuplicateToolAndPromptIds(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        java.util.Map<String, String> tools = new java.util.HashMap<>();
        for (io.tesseraql.yaml.manifest.ToolFile tool : manifest.tools()) {
            checkDuplicateId(appHome, "tool", tool.definition().id(), tool.source(), tools,
                    findings);
        }
        java.util.Map<String, String> prompts = new java.util.HashMap<>();
        for (io.tesseraql.yaml.manifest.PromptFile prompt : manifest.prompts()) {
            checkDuplicateId(appHome, "prompt", prompt.id(), prompt.source(), prompts, findings);
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

    private void checkDuplicateId(Path appHome, String kind, String id, Path file,
            java.util.Map<String, String> seen, List<LintFinding> findings) {
        if (id == null || id.isBlank()) {
            return;
        }
        String source = appHome.relativize(file).toString().replace('\\', '/');
        String previous = seen.putIfAbsent(id, source);
        if (previous != null) {
            findings.add(new LintFinding("TQL-MCP-1014", "error", source,
                    "MCP " + kind + " id '" + id + "' is already declared by " + previous
                            + " - the mcp/ folders organize files and name nothing, so two"
                            + " documents in different folders share one namespace"));
        }
    }
}
