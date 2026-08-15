package io.tesseraql.runtime;

import io.tesseraql.security.SecurityConfig;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.apikey.ApiKeyConfig;
import io.tesseraql.security.apikey.ApiKeyConfig.ApiKeyClient;
import io.tesseraql.security.mtls.MtlsConfig;
import io.tesseraql.security.mtls.MtlsConfig.MtlsClient;
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

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(SecurityConfigFactory.class);

    private SecurityConfigFactory() {
    }

    public static SecurityConfig build(AppConfig config) {
        Map<String, Policy> policies = new LinkedHashMap<>();
        Object raw = config.navigate("tesseraql.security.policies");
        if (raw instanceof Map<?, ?> policyMap) {
            policyMap.forEach((id, spec) -> policies.put(String.valueOf(id),
                    parsePolicy(String.valueOf(id), spec)));
        }
        return new SecurityConfig(policies, parseJwt(config), parseApiKeys(config),
                parseMtls(config));
    }

    @SuppressWarnings("unchecked")
    private static MtlsConfig parseMtls(AppConfig config) {
        if (!(config.navigate("tesseraql.security.mtls.clients") instanceof Map<?, ?> raw)) {
            return null;
        }
        Map<String, MtlsClient> clients = new LinkedHashMap<>();
        raw.forEach((id, spec) -> {
            if (spec instanceof Map<?, ?> client) {
                String prefix = "tesseraql.security.mtls.clients." + id + ".";
                String status = config.getString(prefix + "status").orElse("ACTIVE");
                clients.put(String.valueOf(id), new MtlsClient(
                        config.getString(prefix + "subjectDn").orElse(null),
                        sanMatcher(config, String.valueOf(id), prefix),
                        config.getString(prefix + "sha256").orElse(null),
                        config.getString(prefix + "subject").orElse(null),
                        config.getString(prefix + "tenantId").orElse(null),
                        stringList(((Map<String, Object>) client).get("roles")),
                        stringList(((Map<String, Object>) client).get("permissions")),
                        !"DISABLED".equalsIgnoreCase(status)));
            }
        });
        return new MtlsConfig(
                config.getString("tesseraql.security.mtls.forwardedHeader").orElse(null),
                config.getString("tesseraql.security.mtls.trustBundle").orElse(null),
                duration(config, "tesseraql.security.mtls.clockSkew"),
                clients);
    }

    /**
     * TQL-SEC-4066: the untyped {@code san:} matcher was removed — a certificate identity has to
     * name the kind of Subject Alternative Name it means.
     */
    private static final io.tesseraql.core.error.TqlErrorCode UNTYPED_SAN = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.SEC, 4066);

    /**
     * The client's type-qualified SAN matcher, or null when it identifies by DN or fingerprint.
     *
     * <p>The removed untyped {@code san:} throws rather than being ignored: dropping it would leave
     * the client with no matcher at all, and a service caller that silently stops authenticating is
     * exactly the failure this grammar change exists to prevent. Lint reports the same code at
     * build time; this is the backstop for a config that never ran through it.
     */
    private static MtlsConfig.SanMatcher sanMatcher(AppConfig config, String id, String prefix) {
        if (config.getString(prefix + "san").isPresent()) {
            throw new io.tesseraql.core.error.TqlException(UNTYPED_SAN,
                    "mTLS client '" + id + "' declares the removed untyped san:; name the kind"
                            + " with sanDns/sanUri/sanEmail/sanIp so a certificate's name of one"
                            + " kind cannot satisfy a matcher meaning another");
        }
        for (MtlsConfig.SanType type : MtlsConfig.SanType.values()) {
            String key = "san" + type.name().charAt(0)
                    + type.name().substring(1).toLowerCase(java.util.Locale.ROOT);
            String value = config.getString(prefix + key).orElse(null);
            if (value != null) {
                return new MtlsConfig.SanMatcher(type, value);
            }
        }
        return null;
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
                    java.util.Optional<Policy.Rule> parsed = parseRule((Map<String, Object>) rule);
                    if (parsed.isPresent()) {
                        rules.add(parsed.get());
                    } else {
                        // An unrecognized rule shape (a typo'd role:/permission:/claim:) was
                        // dropped silently — a policy whose anyOf rules are all unrecognized
                        // becomes deny-all, and an operator debugging the 403 had nothing to go on.
                        LOG.warn("Security policy '{}' has an unrecognized rule (keys: {}) — it is "
                                + "ignored; a policy with no rules denies everyone", id,
                                ((Map<?, ?>) rule).keySet());
                    }
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

    /** TQL-SEC-4048: a JWT configuration that can validate a token but names no audience. */
    private static final io.tesseraql.core.error.TqlErrorCode JWT_AUDIENCE_MISSING = new io.tesseraql.core.error.TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.SEC, 4048);

    private static JwtConfig parseJwt(AppConfig config) {
        // JWT auth is enabled by an HS256 secret or any RS256 key source (publicKey/jwksUri); the
        // jwt block existing on its own is not enough, so an app without bearer auth binds nothing.
        String secret = config.getString("tesseraql.security.jwt.secret").orElse(null);
        String publicKey = config.getString("tesseraql.security.jwt.publicKey").orElse(null);
        String jwksUri = config.getString("tesseraql.security.jwt.jwksUri").orElse(null);
        if (secret == null && publicKey == null && jwksUri == null) {
            return null;
        }
        // The build-time lint is not enough on its own: nothing runs AppLinter at startup, so a
        // configuration that reached a running process unlinted would silently take the
        // empty-audience path, which checks nothing. Refusing here makes TQL-SEC-4048 a boot
        // failure as well as a build failure, on the same condition and with the same code
        // (docs/audit-hardening.md Decision 1).
        List<String> audience = audiences(config);
        if (audience.isEmpty()) {
            throw new io.tesseraql.core.error.TqlException(JWT_AUDIENCE_MISSING,
                    "tesseraql.security.jwt is configured without an audience, so any token the"
                            + " issuer minted for any other relying party would be accepted;"
                            + " declare tesseraql.security.jwt.audience with the identifier(s)"
                            + " this application answers to");
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
                audience,
                clockSkew,
                config.getBoolean("tesseraql.security.jwt.requireExpiration", true),
                config.getString("tesseraql.security.jwt.rolesClaim").orElse(null),
                config.getString("tesseraql.security.jwt.permissionsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.groupsClaim").orElse(null),
                config.getString("tesseraql.security.jwt.tenantClaim").orElse(null),
                config.getString("tesseraql.security.jwt.loginClaim").orElse(null),
                config.getString("tesseraql.security.jwt.nameClaim").orElse(null));
    }

    /**
     * The declared audiences, written either as one string or as a list.
     *
     * <p>Every other {@code jwt} key is read through {@code getString}, through which a list cannot
     * arrive at all — so this one navigates instead. The claim side and the config side need two
     * different coercions and this is the config half: {@code aud} is string-or-array in the token,
     * {@code audience} is string-or-list in the YAML, and the model holds a list either way.
     */
    private static List<String> audiences(AppConfig config) {
        Object declared = config.navigate("tesseraql.security.jwt.audience");
        if (declared instanceof List<?> list) {
            return stringList(list);
        }
        if (declared instanceof String single && !single.isBlank()) {
            return List.of(single.trim());
        }
        return List.of();
    }

    private static java.time.Duration duration(AppConfig config, String key) {
        return config.getString(key).map(io.tesseraql.core.util.Durations::parse).orElse(null);
    }
}
