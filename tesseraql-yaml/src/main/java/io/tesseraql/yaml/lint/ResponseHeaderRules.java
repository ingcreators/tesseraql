package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.List;

/**
 * Response-header defaults and the documents that redeclare them.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class ResponseHeaderRules implements LintRule {

    private static final String INVALID_RESPONSE_HEADER_DEFAULTS = "TQL-SEC-4135";

    private static final String RESPONSE_HEADER_RESTATES_DEFAULT = "TQL-SEC-4133";

    private static final String RESPONSE_HEADER_WEAKENS_DEFAULT = "TQL-SEC-4134";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintResponseHeaderDefaults(context.appHome(), manifest,
                manifest.config(), findings);
    }

    /**
     * Lints routes against the app-wide default response headers (docs/route-defaults.md): a
     * route restating a default identically is leftover copy-paste the default replaces, and a
     * route suppressing or wildcard-broadening one is weakening a security control — either
     * deliberate (own the override) or the drift the defaults exist to end. Only routes are
     * compared; with no declared defaults there is nothing to lint.
     */
    void lintResponseHeaderDefaults(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        io.tesseraql.yaml.config.ResponseHeaderDefaults defaults;
        try {
            defaults = io.tesseraql.yaml.config.ResponseHeaderDefaults.from(config);
        } catch (io.tesseraql.core.error.TqlException ex) {
            // The manifest loader does not parse this key; surface the malformed map here.
            findings.add(new LintFinding(INVALID_RESPONSE_HEADER_DEFAULTS, ERROR, "config",
                    ex.getMessage()));
            return;
        }
        if (defaults.isEmpty()) {
            return;
        }
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            if (response == null) {
                continue;
            }
            String source = appHome.relativize(route.source()).toString();
            // Both response kinds carry the block and both receive the defaults, so both are
            // linted against them. Checking only HTML left a JSON route free to restate or weaken
            // a default unremarked once the merge reached it (docs/route-defaults.md).
            if (response.html() != null) {
                lintAgainstDefaults(route, response.html().headers(), defaults, source, findings);
            }
            if (response.json() != null) {
                lintAgainstDefaults(route, response.json().headers(), defaults, source, findings);
            }
        }
    }

    /** One response's declared headers against the app-wide defaults they merge under. */
    private void lintAgainstDefaults(RouteFile route, java.util.Map<String, Object> declaredHeaders,
            io.tesseraql.yaml.config.ResponseHeaderDefaults defaults, String source,
            List<LintFinding> findings) {
        for (var entry : declaredHeaders.entrySet()) {
            String name = entry.getKey();
            String declared = String.valueOf(entry.getValue());
            String fallback = defaults.headers().get(name);
            if (fallback == null) {
                continue;
            }
            if (declared.equals(fallback)) {
                findings.add(new LintFinding(RESPONSE_HEADER_RESTATES_DEFAULT, WARNING, source,
                        "Route '" + route.definition().id() + "' restates the default"
                                + " response header '" + name + "' — the app default"
                                + " already sends it"));
            } else if (io.tesseraql.yaml.config.ResponseHeaderDefaults.UNSET.equals(declared)) {
                findings.add(new LintFinding(RESPONSE_HEADER_WEAKENS_DEFAULT, WARNING, source,
                        "Route '" + route.definition().id() + "' suppresses the default"
                                + " response header '" + name + "' — confirm the response must"
                                + " not send it"));
            } else if (declared.contains("*") && !fallback.contains("*")) {
                findings.add(new LintFinding(RESPONSE_HEADER_WEAKENS_DEFAULT, WARNING, source,
                        "Route '" + route.definition().id() + "' overrides the default"
                                + " response header '" + name + "' with a wildcard the"
                                + " default does not carry — confirm the broadening"));
            }
        }
    }
}
