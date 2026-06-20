package io.tesseraql.runtime;

import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The login-method model for the bundled login page (the {@code auth.loginMethods} service): which
 * sign-in methods are available and where each begins, derived from config. Password login is always
 * wired ({@link LoginRouteBuilder}); the OIDC and SAML buttons appear when those extensions are
 * enabled ({@code tesseraql.oidc.enabled} / {@code tesseraql.saml.enabled}) — and an operator can
 * "switch" to SSO-only by turning the password form off
 * ({@code tesseraql.console.login.password.enabled: false}). Template-ready (maps and scalars only).
 */
final class LoginMethods {

    private LoginMethods() {
    }

    static Map<String, Object> of(AppConfig config) {
        boolean oidc = flag(config, "tesseraql.oidc.enabled");
        boolean saml = flag(config, "tesseraql.saml.enabled");
        boolean password = config.getString("tesseraql.console.login.password.enabled")
                .map(Boolean::parseBoolean).orElse(true);
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("password", password);
        model.put("oidc", method(oidc, "/_tesseraql/oidc/login"));
        model.put("saml", method(saml, "/_tesseraql/saml/login"));
        model.put("sso", oidc || saml);
        // First-login guidance: the seed step is documented rather than auto-run (no default admin).
        model.put("seedHint",
                "tesseraql identity-schema --admin-login <id> --admin-password-file <file>");
        return model;
    }

    private static Map<String, Object> method(boolean enabled, String url) {
        Map<String, Object> method = new LinkedHashMap<>();
        method.put("enabled", enabled);
        method.put("url", url);
        return method;
    }

    private static boolean flag(AppConfig config, String key) {
        return config.getString(key).map(Boolean::parseBoolean).orElse(false);
    }
}
