package io.tesseraql.runtime;

import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.apikey.ApiKeyConfig;
import io.tesseraql.security.apikey.ApiKeyConfig.ApiKeyClient;
import io.tesseraql.security.policy.Policy;
import io.tesseraql.yaml.config.AppConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SecurityConfig} from {@code tesseraql.security.*} (design ch. 10.9.1, 11).
 */
public final class SecurityConfigFactory {

    private SecurityConfigFactory() {
    }

    public static SecurityConfig build(AppConfig config) {
        Map<String, Policy> policies = new LinkedHashMap<>();
        Object raw = config.navigate("tesseraql.security.policies");
        if (raw instanceof Map<?, ?> policyMap) {
            policyMap.forEach((id, spec) -> policies.put(String.valueOf(id),
                    parsePolicy(String.valueOf(id), spec)));
        }
        return new SecurityConfig(policies, parseJwt(config), parseApiKeys(config));
    }

    @SuppressWarnings("unchecked")
    private static ApiKeyConfig parseApiKeys(AppConfig config) {
        if (!(config.navigate("tesseraql.security.apiKeys.clients") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, ApiKeyClient> clients = new LinkedHashMap<>();
        raw.forEach((id, spec) -> {
            if (spec instanceof Map<?, ?> client) {
                String prefix = "tesseraql.security.apiKeys.clients." + id + ".";
                String status = config.getString(prefix + "status").orElse("ACTIVE");
                clients.put(String.valueOf(id), new ApiKeyClient(
                        config.getString(prefix + "secretHash").orElse(null),
                        config.getString(prefix + "subject").orElse(null),
                        config.getString(prefix + "tenantId").orElse(null),
                        stringList(((Map<String, Object>) client).get("roles")),
                        stringList(((Map<String, Object>) client).get("permissions")),
                        !"DISABLED".equalsIgnoreCase(status)));
            }
        });
        return new ApiKeyConfig(config.getString("tesseraql.security.apiKeys.header").orElse(null),
                clients);
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(String.valueOf(element)));
            return result;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Policy parsePolicy(String id, Object spec) {
        List<Policy.Rule> rules = new ArrayList<>();
        if (spec instanceof Map<?, ?> map && map.get("anyOf") instanceof List<?> anyOf) {
            for (Object element : anyOf) {
                if (element instanceof Map<?, ?> rule) {
                    parseRule((Map<String, Object>) rule).ifPresent(rules::add);
                }
            }
        }
        return new Policy(id, rules);
    }

    private static java.util.Optional<Policy.Rule> parseRule(Map<String, Object> rule) {
        if (rule.get("role") != null) {
            return java.util.Optional.of(Policy.Rule.ofRole(String.valueOf(rule.get("role"))));
        }
        if (rule.get("permission") != null) {
            return java.util.Optional
                    .of(Policy.Rule.ofPermission(String.valueOf(rule.get("permission"))));
        }
        if (rule.get("claim") instanceof Map<?, ?> claim) {
            return java.util.Optional.of(Policy.Rule.ofClaim(
                    String.valueOf(claim.get("name")), String.valueOf(claim.get("value"))));
        }
        return java.util.Optional.empty();
    }

    private static JwtConfig parseJwt(AppConfig config) {
        // JWT auth is enabled by an HS256 secret or any RS256 key source (publicKey/jwksUri); the
        // jwt block existing on its own is not enough, so an app without bearer auth binds nothing.
        String secret = config.getString("tesseraql.security.jwt.secret").orElse(null);
        String publicKey = config.getString("tesseraql.security.jwt.publicKey").orElse(null);
        String jwksUri = config.getString("tesseraql.security.jwt.jwksUri").orElse(null);
        if (secret == null && publicKey == null && jwksUri == null) {
            return null;
        }
        java.time.Duration clockSkew = duration(config, "tesseraql.security.jwt.clockSkew");
        SecurityConfig.JwksConfig jwks = new SecurityConfig.JwksConfig(
                duration(config, "tesseraql.security.jwt.jwks.cacheTtl"),
                duration(config, "tesseraql.security.jwt.jwks.refreshFloor"),
                duration(config, "tesseraql.security.jwt.jwks.requestTimeout"));
        return new JwtConfig(
                config.getString("tesseraql.security.jwt.algorithm").orElse(null),
                secret,
                publicKey,
                jwksUri,
                jwks,
                config.getString("tesseraql.security.jwt.issuer").orElse(null),
                clockSkew,
                config.getString("tesseraql.security.jwt.rolesClaim").orElse(null),
                config.getString("tesseraql.security.jwt.permissionsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.groupsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.tenantClaim").orElse(null),
                config.getString("tesseraql.security.jwt.loginClaim").orElse(null),
                config.getString("tesseraql.security.jwt.nameClaim").orElse(null));
    }

    private static java.time.Duration duration(AppConfig config, String key) {
        return config.getString(key).map(io.tesseraql.core.util.Durations::parse).orElse(null);
    }
}
