package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Root-absolute URLs in an application served under a base path.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class BasePathRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintBasePathLinks(context.appHome(), manifest.config(), findings);
    }

    /** Attribute names whose value the browser resolves as a URL. */
    private static final String URL_ATTRIBUTES = "href|src|action|formaction|hx-get|hx-post"
            + "|hx-put|hx-patch|hx-delete|sse-connect";

    /**
     * A root-absolute URL in the application's own markup: {@code href="/orders"}, or the same
     * written as a Thymeleaf literal substitution, {@code th:href="|/orders/${id}|"}.
     */
    private static final Pattern ROOT_ABSOLUTE_URL = Pattern.compile(
            "(?<![\\w:-])(?:th:)?(" + URL_ATTRIBUTES + ")=\"\\|?(/(?!/)[^\"]*)\"");

    /**
     * TQL-TPL-2004, a warning: an application served under {@code tesseraql.http.basePath}
     * emits a URL rooted at the origin, where its own runtime does not answer
     * (docs/base-path.md decision 3). The remedy is a link expression — {@code th:href="@{/x}"}
     * — which resolves against the prefix.
     *
     * <p>A warning rather than an error, and only for applications that configured a prefix: a
     * page may legitimately link off-site or to a path outside its own mount point, and nothing
     * here can tell which. Applications served at the root of their origin are never told
     * anything, so the lint is silent for everyone until the day it is useful.
     */
    void lintBasePathLinks(Path appHome, AppConfig config, List<LintFinding> findings) {
        if (io.tesseraql.core.http.BasePaths.normalize(
                config.getString("tesseraql.http.basePath").orElse(null)).isEmpty()) {
            return;
        }
        for (String tree : new String[]{"web", "templates"}) {
            Path root = appHome.resolve(tree);
            if (!java.nio.file.Files.isDirectory(root)) {
                continue;
            }
            try (var files = java.nio.file.Files.walk(root)) {
                for (Path file : files.filter(f -> f.toString().endsWith(".html")).sorted()
                        .toList()) {
                    String source = appHome.relativize(file).toString().replace('\\', '/');
                    Matcher urls = ROOT_ABSOLUTE_URL.matcher(java.nio.file.Files.readString(file));
                    while (urls.find()) {
                        findings.add(new LintFinding("TQL-TPL-2004", "warning", source,
                                urls.group(1) + "=\"" + urls.group(2) + "\" is rooted at the"
                                        + " origin, and this application is served under"
                                        + " tesseraql.http.basePath — write th:" + urls.group(1)
                                        + "=\"@{" + urls.group(2) + "}\" unless the link is"
                                        + " deliberately outside the application"));
                    }
                }
            } catch (java.io.IOException ex) {
                findings.add(new LintFinding("TQL-TPL-2004", "warning", tree,
                        "templates could not be read: " + ex.getMessage()));
            }
        }
    }
}
