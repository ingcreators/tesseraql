package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * YAML documents that sit in a loadable tree but no loader claims.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class UnclaimedFileRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintUnclaimedFiles(context.appHome(), findings);
    }

    /** HTTP method stems a {@code web/**} route file may carry (mirrors {@code ManifestLoader}). */
    private static final Set<String> HTTP_METHOD_STEMS = Set.of("get", "post", "put", "patch",
            "delete", "head", "options");

    /**
     * Reports YAML documents that sit in a loadable app tree but no loader claims (TQL-APP-4205):
     * a {@code .yaml} extension where every loader filters on {@code .yml}, a {@code web/**} file
     * whose stem is not an HTTP method, or a {@code domains/rules/decisions/calendars} file in a
     * subdirectory (those loaders are non-recursive). Such a file simply does not exist at runtime
     * — the route 404s, the domain reference is unknown — with nothing pointing at the filename.
     */
    void lintUnclaimedFiles(Path appHome, List<LintFinding> findings) {
        // Recursive route/document trees: any *.yml is claimed; a *.yaml is the giveaway.
        sweepYamlTree(appHome, "web", findings, (rel, stem, isYaml) -> {
            if (isYaml) {
                return "expected .yml, not .yaml";
            }
            // .view.yml (views) and .sample.yml (Studio preview fixtures) are legitimate.
            if (rel.endsWith(".view.yml") || rel.endsWith(".sample.yml")) {
                return null;
            }
            return HTTP_METHOD_STEMS.contains(stem)
                    ? null
                    : "a web/ route file must be named <method>.yml (get|post|put|patch|delete"
                            + "|head|options), or <name>.view.yml for a view";
        });
        for (String tree : List.of("batch", "workflow", "scope", "consume", "mcp", "attachments",
                "tests")) {
            sweepYamlTree(appHome, tree, findings,
                    (rel, stem, isYaml) -> isYaml ? "expected .yml, not .yaml" : null);
        }
        // Non-recursive shared-definition trees: a *.yaml, or a *.yml in a subdirectory, is dropped.
        for (String tree : List.of("domains", "rules", "decisions", "calendars")) {
            sweepYamlTree(appHome, tree, findings, (rel, stem, isYaml) -> {
                if (isYaml) {
                    return "expected .yml, not .yaml";
                }
                // rel is tree-relative; a separator means it is nested below the tree root.
                return rel.indexOf('/') >= 0
                        ? tree + "/ is loaded non-recursively; move this file to the " + tree
                                + "/ root"
                        : null;
            });
        }
    }

    /** A per-file verdict: a finding message, or {@code null} when the file is fine. */
    private interface UnclaimedRule {
        String check(String treeRelativePath, String stem, boolean isYaml);
    }

    private void sweepYamlTree(Path appHome, String tree, List<LintFinding> findings,
            UnclaimedRule rule) {
        Path root = appHome.resolve(tree);
        if (!Files.isDirectory(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                boolean isYaml = name.endsWith(".yaml");
                if (!isYaml && !name.endsWith(".yml")) {
                    return;
                }
                String treeRelative = root.relativize(file).toString().replace('\\', '/');
                String stem = name.replaceFirst("\\.ya?ml$", "");
                String problem = rule.check(treeRelative, stem, isYaml);
                if (problem != null) {
                    findings.add(new LintFinding("TQL-APP-4205", "error",
                            LintSupport.relative(appHome, file),
                            "'" + tree + "/" + treeRelative + "' is not loaded — " + problem));
                }
            });
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
