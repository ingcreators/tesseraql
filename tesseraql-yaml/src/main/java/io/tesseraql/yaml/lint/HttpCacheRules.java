package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.model.RouteDefinition;
import java.util.List;

/**
 * A route's HTTP cache declaration ({@code cache:}).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class HttpCacheRules {

    private static final String INVALID_CACHE = "TQL-YAML-1025";

    private HttpCacheRules() {
    }

    /**
     * cache: lints (docs/response-shaping.md, "HTTP caching") — TQL-YAML-1025: caching is a
     * query-recipe key (a command's response must never come from a cache); {@code public}
     * visibility only on {@code auth: public} routes (an authenticated response is
     * per-principal by definition); durations must parse.
     */
    static void lintHttpCache(RouteDefinition definition, String source,
            List<LintFinding> findings) {
        var cache = definition.cache();
        if (cache == null) {
            return;
        }
        String recipe = definition.recipe();
        if (!"query-json".equals(recipe) && !"query-html".equals(recipe)
                && !"page".equals(recipe)) {
            findings.add(new LintFinding(INVALID_CACHE, ERROR, source,
                    "cache: is only supported on query recipes"
                            + " (query-json, query-html, page), not '" + recipe + "'"));
        }
        String visibility = cache.effectiveVisibility();
        if (!"private".equals(visibility) && !"public".equals(visibility)) {
            findings.add(new LintFinding(INVALID_CACHE, ERROR, source,
                    "cache.visibility must be 'private' or 'public', got '" + visibility
                            + "'"));
        } else if ("public".equals(visibility) && (definition.security() == null
                || !"public".equals(definition.security().auth()))) {
            findings.add(new LintFinding(INVALID_CACHE, ERROR, source,
                    "cache.visibility: public is only allowed on auth: public routes - an"
                            + " authenticated response is per-principal"));
        }
        for (String duration : new String[]{cache.maxAge(), cache.staleWhileRevalidate()}) {
            if (duration == null || duration.isBlank()) {
                continue;
            }
            try {
                io.tesseraql.core.util.Durations.toMillis(duration);
            } catch (RuntimeException ex) {
                findings.add(new LintFinding(INVALID_CACHE, ERROR, source,
                        "cache: unparseable duration '" + duration + "'"));
            }
        }
    }
}
