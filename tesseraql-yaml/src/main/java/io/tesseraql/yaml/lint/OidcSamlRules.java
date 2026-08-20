package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;
import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.List;

/**
 * OIDC and SAML federation configuration.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class OidcSamlRules implements LintRule {

    private static final String OIDC_WITHOUT_DISCOVERY_URI = "TQL-SEC-4050";

    private static final String OIDC_DISCOVERY_URI_NOT_HTTPS = "TQL-SEC-4051";

    private static final String OIDC_WITHOUT_CLIENT_ID = "TQL-SEC-4052";

    private static final String OIDC_WITHOUT_REDIRECT_URI = "TQL-SEC-4053";

    private static final String SAML_WITHOUT_ACS_URL = "TQL-SEC-4092";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintOidcConfig(manifest.config(), findings);
        lintSamlConfig(manifest.config(), findings);
    }

    /**
     * Lints the OIDC relying-party config (roadmap Phase 25): when enabled, it must declare a
     * https (or loopback-http) discovery URI, a client id, and a redirect URI — caught statically
     * so a misconfigured login fails at lint, not at the first redirect. Reads raw config nodes.
     */
    void lintOidcConfig(AppConfig config, List<LintFinding> findings) {
        if (!"true".equalsIgnoreCase(rawString(config, "tesseraql.oidc.enabled"))) {
            return;
        }
        String discoveryUri = rawString(config, "tesseraql.oidc.discoveryUri");
        if (discoveryUri == null) {
            findings.add(new LintFinding(OIDC_WITHOUT_DISCOVERY_URI, ERROR, "config",
                    "OIDC is enabled but tesseraql.oidc.discoveryUri is not configured"));
        } else if (!discoveryUri.contains("${") && !isHttpsOrLoopback(discoveryUri)) {
            findings.add(new LintFinding(OIDC_DISCOVERY_URI_NOT_HTTPS, ERROR, "config",
                    "OIDC tesseraql.oidc.discoveryUri must be https"
                            + " (loopback http is allowed for development)"));
        }
        if (rawString(config, "tesseraql.oidc.clientId") == null) {
            findings.add(new LintFinding(OIDC_WITHOUT_CLIENT_ID, ERROR, "config",
                    "OIDC is enabled but tesseraql.oidc.clientId is not configured"));
        }
        if (rawString(config, "tesseraql.oidc.redirectUri") == null) {
            findings.add(new LintFinding(OIDC_WITHOUT_REDIRECT_URI, ERROR, "config",
                    "OIDC is enabled but tesseraql.oidc.redirectUri is not configured"));
        }
    }

    /**
     * Lints the SAML service-provider config (roadmap Phase 26): an enabled SP without
     * {@code sp.acsUrl} silently turns off the SubjectConfirmation {@code Recipient} check — the
     * assertion is then accepted no matter which service provider it was addressed to, so an
     * assertion captured at another SP of the same IdP replays here. The URL stays optional
     * (IdP-initiated-only deployments have no ACS to advertise), so this is a warning and not an
     * error: exactly the {@code TQL-SEC-4065} stance for the analogous mTLS {@code trustBundle},
     * which is the asymmetry this closes. Reads raw config nodes — never resolving secrets.
     */
    void lintSamlConfig(AppConfig config, List<LintFinding> findings) {
        if (!"true".equalsIgnoreCase(rawString(config, "tesseraql.saml.enabled"))) {
            return;
        }
        if (rawString(config, "tesseraql.saml.sp.acsUrl") == null) {
            findings.add(new LintFinding(SAML_WITHOUT_ACS_URL, WARNING, "config",
                    "SAML is enabled but declares no tesseraql.saml.sp.acsUrl; the assertion's"
                            + " SubjectConfirmation recipient is not checked, and neither the login"
                            + " route nor the SP metadata endpoint is published"));
        }
    }

    private static String rawString(AppConfig config, String path) {
        Object value = config.navigate(path);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean isHttpsOrLoopback(String uri) {
        if (uri.startsWith("https://")) {
            return true;
        }
        if (uri.startsWith("http://")) {
            String host = uri.substring("http://".length());
            return host.startsWith("localhost") || host.startsWith("127.0.0.1")
                    || host.startsWith("[::1]") || host.startsWith("::1");
        }
        return false;
    }
}
