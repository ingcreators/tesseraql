package io.tesseraql.runtime;

import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.SecurityConfig.JwtConfig;
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
            policyMap.forEach((id, spec) ->
                    policies.put(String.valueOf(id), parsePolicy(String.valueOf(id), spec)));
        }
        return new SecurityConfig(policies, parseJwt(config));
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
            return java.util.Optional.of(Policy.Rule.ofPermission(String.valueOf(rule.get("permission"))));
        }
        if (rule.get("claim") instanceof Map<?, ?> claim) {
            return java.util.Optional.of(Policy.Rule.ofClaim(
                    String.valueOf(claim.get("name")), String.valueOf(claim.get("value"))));
        }
        return java.util.Optional.empty();
    }

    private static JwtConfig parseJwt(AppConfig config) {
        String secret = config.getString("tesseraql.security.jwt.secret").orElse(null);
        if (secret == null) {
            return null;
        }
        return new JwtConfig(
                secret,
                config.getString("tesseraql.security.jwt.issuer").orElse(null),
                config.getString("tesseraql.security.jwt.rolesClaim").orElse(null),
                config.getString("tesseraql.security.jwt.permissionsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.groupsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.tenantClaim").orElse(null),
                config.getString("tesseraql.security.jwt.loginClaim").orElse(null),
                config.getString("tesseraql.security.jwt.nameClaim").orElse(null));
    }
}
