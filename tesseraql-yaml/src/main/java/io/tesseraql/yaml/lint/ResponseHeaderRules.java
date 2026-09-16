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

    private static final String RESPONSE_HEADER_RESERVED = "TQL-SEC-4139";

    private static final String RESPONSE_HEADER_CONTROL = "TQL-SEC-4151";

    /** A declared header whose name is not a token the wire can carry. */
    private static final String RESPONSE_HEADER_NAME = "TQL-SEC-4152";

    /** A declared {@code Content-Disposition} building a filename from a placeholder. */
    private static final String RESPONSE_HEADER_DISPOSITION = "TQL-SEC-4153";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintReservedNames(context.appHome(), manifest, findings);
        lintResponseHeaderDefaults(context.appHome(), manifest,
                manifest.config(), findings);
    }

    /**
     * Refuses a declared response header the transport owns (docs/vertx-native.md decision 1's
     * surviving half): a `Content-Length` disagreeing with the body the edge writes truncates
     * the response or hangs the keep-alive connection, `Connection`/`Transfer-Encoding` are
     * smuggling-shaped protocol violations, and the `tql.` namespace exists because it never
     * leaves. The HTTP edge drops these at the wire as the runtime backstop; the lint is where
     * an author learns it, at build time, with the route named.
     */
    void lintReservedNames(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        for (RouteFile route : manifest.routes()) {
            var response = route.definition().response();
            if (response == null) {
                continue;
            }
            String source = appHome.relativize(route.source()).toString();
            if (response.html() != null) {
                lintReserved(route, response.html().headers(), source, findings);
            }
            if (response.json() != null) {
                lintReserved(route, response.json().headers(), source, findings);
            }
        }
    }

    private void lintReserved(RouteFile route, java.util.Map<String, Object> declaredHeaders,
            String source, List<LintFinding> findings) {
        for (var entry : declaredHeaders.entrySet()) {
            String name = entry.getKey();
            // The name itself, before what it is (docs/audit-low-leads.md slice 9, DN-02a):
            // no check anywhere read a header name's characters, so a key with a space in it
            // linted clean and hung the route — Vert.x refuses the name inside the transport,
            // past the edge's 500. The edge refuses it on the route's thread now; here it is
            // named at build time.
            String notAToken = io.tesseraql.core.http.ReservedHeaders.notAToken(name);
            if (notAToken != null) {
                findings.add(new LintFinding(RESPONSE_HEADER_NAME, ERROR, source,
                        "Route '" + route.definition().id() + "' declares the response header '"
                                + name + "', whose name " + notAToken
                                + " — the edge refuses it on every request"));
                continue;
            }
            if (io.tesseraql.core.http.ReservedHeaders.neverDeclared(name)) {
                findings.add(new LintFinding(RESPONSE_HEADER_RESERVED, ERROR, source,
                        "Route '" + route.definition().id() + "' declares the response header '"
                                + name + "', which the transport owns — framing and connection"
                                + " control are computed from the body the server writes, and"
                                + " the tql. namespace never leaves the runtime"));
            }
            if ("content-disposition".equalsIgnoreCase(name)
                    && entry.getValue() instanceof String value
                    && placeholderFilename(value)) {
                // A download's name has a helper (docs/download-name-and-bytes.md): the
                // response.file: recipe's filename: quotes and encodes it (RFC 6266), and a
                // placeholder written into a Content-Disposition by hand is neither — a
                // quote in the value ends the name and starts a parameter, a non-ASCII name
                // folds to '?' on the wire. A literal disposition is left alone: inline has
                // no other spelling (docs/audit-low-leads.md slice 9, DN-02c).
                findings.add(new LintFinding(RESPONSE_HEADER_DISPOSITION, WARNING, source,
                        "Route '" + route.definition().id() + "' builds a Content-Disposition"
                                + " filename from a placeholder — the value is neither quoted"
                                + " nor encoded on the wire; name a download with"
                                + " response.file: filename:, which the framework quotes and"
                                + " encodes"));
            }
            // A literal control character in the declared text is refused at the edge on every
            // request (docs/edge-hygiene.md E3); here it is named at build time. A value a
            // placeholder brings in is judged only there. A map or list value serializes to
            // JSON, which escapes its controls, so only the string form is read.
            if (entry.getValue() instanceof String value) {
                int control = io.tesseraql.yaml.config.ResponseHeaderDefaults.controlAt(value);
                if (control >= 0) {
                    findings.add(new LintFinding(RESPONSE_HEADER_CONTROL, ERROR, source,
                            "Route '" + route.definition().id() + "' declares the response"
                                    + " header '" + name + "' with the control character "
                                    + io.tesseraql.yaml.config.ResponseHeaderDefaults
                                            .unicodeName(value.charAt(control))
                                    + " — the edge refuses it on every request"));
                }
            }
        }
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
            // The manifest loader does not parse this key; surface the malformed map here,
            // under the code the read refused it with — the shape, a value's control
            // character or a name that is not a token (TQL-SEC-4135), a name the transport
            // owns (TQL-SEC-4139) — so lint and boot say the same thing.
            findings.add(new LintFinding(ex.code() == null
                    ? INVALID_RESPONSE_HEADER_DEFAULTS
                    : ex.code().toString(), ERROR, "config", ex.getMessage()));
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

    /** Whether a declared disposition builds its {@code filename=} from a {@code {placeholder}}. */
    private static boolean placeholderFilename(String value) {
        int filename = value.toLowerCase(java.util.Locale.ROOT).indexOf("filename");
        return filename >= 0 && value.indexOf('{', filename) >= 0
                && value.indexOf('}', filename) >= 0;
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
