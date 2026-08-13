package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.RouteFile;
import java.nio.file.Path;
import java.util.List;

/**
 * Mutual-TLS configuration and its typed SAN matchers.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class MtlsConfigRules implements LintRule {

    private static final String MTLS_AUTH_UNCONFIGURED = "TQL-SEC-4060";

    private static final String MTLS_WITHOUT_FORWARDED_HEADER = "TQL-SEC-4061";

    private static final String MTLS_WITHOUT_TRUST_BUNDLE = "TQL-SEC-4065";

    private static final String MTLS_REMOVED_UNTYPED_SAN = "TQL-SEC-4066";

    private static final String MTLS_CLIENT_WITHOUT_MATCHER = "TQL-SEC-4062";

    private static final String MTLS_CLIENT_WITH_SEVERAL_MATCHERS = "TQL-SEC-4063";

    private static final String MTLS_CLIENT_WITHOUT_GRANTS = "TQL-SEC-4064";

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintMtlsConfig(context.appHome(), manifest, manifest.config(), findings);
    }

    /** The type-qualified Subject Alternative Name matchers an mTLS client may declare. */
    private static final List<String> MTLS_SAN_KEYS = List.of("sanDns", "sanUri", "sanEmail",
            "sanIp");

    /**
     * Lints the mutual-TLS config (roadmap Phase 25): an {@code auth: mtls} route requires mTLS
     * config; the config must name the forwarded-certificate header and each client must declare
     * exactly one certificate matcher (subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256). A missing
     * trustBundle is a warning —
     * without it the runtime does not independently validate the chain and fully trusts the
     * TLS-terminating edge. Reads raw config nodes — never resolving secret placeholders.
     */
    void lintMtlsConfig(Path appHome, AppManifest manifest, AppConfig config,
            List<LintFinding> findings) {
        if (config.navigate("tesseraql.security.mtls") == null) {
            for (RouteFile route : manifest.routes()) {
                io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
                if (security != null && "mtls".equals(security.auth())) {
                    String source = appHome.relativize(route.source()).toString().replace('\\',
                            '/');
                    findings.add(new LintFinding(MTLS_AUTH_UNCONFIGURED, ERROR, source,
                            "Route '" + route.definition().id() + "' declares auth: mtls but no"
                                    + " tesseraql.security.mtls is configured (deny by default)"));
                }
            }
            return;
        }
        if (config.navigate("tesseraql.security.mtls.forwardedHeader") == null) {
            findings.add(new LintFinding(MTLS_WITHOUT_FORWARDED_HEADER, ERROR, "config",
                    "tesseraql.security.mtls declares no forwardedHeader; a forwarded client"
                            + " certificate has no header to be read from"));
        }
        if (config.navigate("tesseraql.security.mtls.trustBundle") == null) {
            findings.add(new LintFinding(MTLS_WITHOUT_TRUST_BUNDLE, WARNING, "config",
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
            // The untyped san: matched a value against every kind of Subject Alternative Name at
            // once, so a certificate carrying it as an email or URI satisfied a matcher that meant
            // DNS. It is gone rather than deprecated: a config kept working while meaning something
            // weaker is the failure this replaces.
            if (client.get("san") != null) {
                findings.add(new LintFinding(MTLS_REMOVED_UNTYPED_SAN, ERROR, "config",
                        "mTLS client '" + id + "' declares the removed untyped san:; name the kind"
                                + " with sanDns/sanUri/sanEmail/sanIp so a certificate's name of"
                                + " one kind cannot satisfy a matcher meaning another"));
            }
            int matchers = 0;
            if (client.get("subjectDn") != null) {
                matchers++;
            }
            for (String typed : MTLS_SAN_KEYS) {
                if (client.get(typed) != null) {
                    matchers++;
                }
            }
            if (client.get("sha256") != null) {
                matchers++;
            }
            if (matchers == 0) {
                findings.add(new LintFinding(MTLS_CLIENT_WITHOUT_MATCHER, ERROR, "config",
                        "mTLS client '" + id + "' declares no certificate matcher; set exactly one"
                                + " of subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256"));
            } else if (matchers > 1) {
                findings.add(new LintFinding(MTLS_CLIENT_WITH_SEVERAL_MATCHERS, ERROR, "config",
                        "mTLS client '" + id + "' declares more than one certificate matcher; set"
                                + " exactly one of subjectDn/sanDns/sanUri/sanEmail/sanIp/sha256"));
            }
            if (client.get("roles") == null && client.get("permissions") == null) {
                findings.add(new LintFinding(MTLS_CLIENT_WITHOUT_GRANTS, WARNING, "config",
                        "mTLS client '" + id + "' grants no roles or permissions; service callers"
                                + " should be least-privilege"));
            }
        });
    }
}
