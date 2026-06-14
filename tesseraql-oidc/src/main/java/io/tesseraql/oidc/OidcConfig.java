package io.tesseraql.oidc;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolved OIDC relying-party configuration, read from {@code tesseraql.oidc.*} (design ch. 10.14,
 * roadmap Phase 25). The provider's endpoints are not here — they are discovered at runtime from
 * {@link #discoveryUri()} (lazily, so a brief provider outage does not fail app startup).
 *
 * @param discoveryUri the OpenID Provider discovery document URL ({@code .well-known/openid-configuration})
 * @param clientId     the registered client id
 * @param clientSecret the client secret, or null for a public (PKCE-only) client
 * @param redirectUri  the registered callback URL (must match what is sent to the token endpoint)
 * @param scopes       requested scopes; {@code openid} is always present
 * @param postLoginUrl a fixed, same-origin path to redirect to after login (default {@code /})
 * @param clockSkew    leeway for the ID token's {@code exp}/{@code nbf}
 * @param claims       ID-token claim names mapped onto the principal
 * @param linkEnabled  resolve/authorize via local identity contracts instead of IdP-asserted roles
 * @param provision    JIT-provision an unknown user when linking
 */
public record OidcConfig(
        String discoveryUri,
        String clientId,
        String clientSecret,
        String redirectUri,
        List<String> scopes,
        String postLoginUrl,
        Duration clockSkew,
        Claims claims,
        boolean linkEnabled,
        boolean provision) {

    public OidcConfig {
        scopes = scopes == null || scopes.isEmpty() ? List.of("openid") : List.copyOf(scopes);
        postLoginUrl = postLoginUrl == null || postLoginUrl.isBlank() ? "/" : postLoginUrl;
        clockSkew = clockSkew == null ? Duration.ZERO : clockSkew;
        claims = claims == null ? new Claims(null, null, null, null, null) : claims;
    }

    /** Whether a confidential client secret is configured (else this is a public PKCE-only client). */
    public boolean confidential() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    /**
     * ID-token claim names mapped onto {@link io.tesseraql.security.Principal} fields. Defaults are
     * the standard OIDC claims.
     */
    public record Claims(String login, String name, String roles, String groups, String tenant) {

        public Claims {
            login = orDefault(login, "preferred_username");
            name = orDefault(name, "name");
            roles = orDefault(roles, "roles");
            groups = orDefault(groups, "groups");
            tenant = orDefault(tenant, "tenant_id");
        }

        private static String orDefault(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value;
        }
    }

    /** Builds the config from {@code tesseraql.oidc.*}. */
    public static OidcConfig parse(AppConfig config) {
        return new OidcConfig(
                config.getString("tesseraql.oidc.discoveryUri").orElse(null),
                config.getString("tesseraql.oidc.clientId").orElse(null),
                config.getString("tesseraql.oidc.clientSecret").orElse(null),
                config.getString("tesseraql.oidc.redirectUri").orElse(null),
                scopes(config),
                config.getString("tesseraql.oidc.postLoginUrl").orElse(null),
                config.getString("tesseraql.oidc.clockSkew").map(Durations::parse).orElse(null),
                new Claims(
                        config.getString("tesseraql.oidc.claims.login").orElse(null),
                        config.getString("tesseraql.oidc.claims.name").orElse(null),
                        config.getString("tesseraql.oidc.claims.roles").orElse(null),
                        config.getString("tesseraql.oidc.claims.groups").orElse(null),
                        config.getString("tesseraql.oidc.claims.tenant").orElse(null)),
                config.getString("tesseraql.oidc.link.enabled").map(Boolean::parseBoolean)
                        .orElse(false),
                config.getString("tesseraql.oidc.link.provision").map(Boolean::parseBoolean)
                        .orElse(false));
    }

    private static List<String> scopes(AppConfig config) {
        Object raw = config.navigate("tesseraql.oidc.scopes");
        List<String> scopes = new ArrayList<>();
        if (raw instanceof List<?> list) {
            list.forEach(scope -> scopes.add(String.valueOf(scope)));
        } else if (raw != null) {
            // A whitespace-separated string is also accepted (e.g. "openid profile email").
            for (String scope : String.valueOf(raw).trim().split("\\s+")) {
                if (!scope.isBlank()) {
                    scopes.add(scope);
                }
            }
        }
        if (scopes.isEmpty()) {
            return List.of("openid");
        }
        if (!scopes.contains("openid")) {
            scopes.add(0, "openid");
        }
        return scopes;
    }
}
