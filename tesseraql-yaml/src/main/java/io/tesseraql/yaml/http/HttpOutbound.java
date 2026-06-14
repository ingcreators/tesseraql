package io.tesseraql.yaml.http;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The outbound HTTP policy configured under {@code tesseraql.http.outbound} (roadmap Phase 26):
 * the egress allow-list, default timeouts, circuit-breaker thresholds, and named credentials an
 * {@code http-call} pipeline step draws on.
 *
 * <p>Egress is deny-by-default: a call may only target a host in {@code allowedHosts}, so an
 * {@code http-call} step can never reach an arbitrary URL (the host stays an
 * allow-listed recipe, not an open Camel endpoint). Credential settings resolve their
 * {@code ${...}} placeholders lazily, per read, so secrets declared through the SecretResolver
 * SPI ({@code ${secret.<provider>.<name>}}) are fetched at call time, never at startup and never
 * into logs or artifacts — mirroring how {@code tesseraql.notifications.channels} handle them.
 */
public final class HttpOutbound {

    /** TQL-YAML-1103: an invalid {@code tesseraql.http.outbound} declaration (fail fast). */
    public static final TqlErrorCode INVALID_CONFIG = new TqlErrorCode(TqlDomain.YAML, 1103);
    /** TQL-BATCH-5308: an {@code http-call} step references an unconfigured credential. */
    public static final TqlErrorCode UNKNOWN_CREDENTIAL = new TqlErrorCode(TqlDomain.BATCH, 5308);

    /** The supported credential types. */
    public static final String BEARER = "bearer";
    public static final String BASIC = "basic";
    public static final String HEADER = "header";

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_CIRCUIT_BREAKER_THRESHOLD = 5;
    private static final Duration DEFAULT_CIRCUIT_BREAKER_OPEN = Duration.ofSeconds(30);

    private final AppConfig config;
    private final boolean present;
    private final List<String> allowedHosts;
    private final Map<String, Credential> credentials;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int circuitBreakerThreshold;
    private final Duration circuitBreakerOpenDuration;

    private HttpOutbound(AppConfig config, boolean present, List<String> allowedHosts,
            Map<String, Credential> credentials, Duration connectTimeout, Duration requestTimeout,
            int circuitBreakerThreshold, Duration circuitBreakerOpenDuration) {
        this.config = config;
        this.present = present;
        this.allowedHosts = List.copyOf(allowedHosts);
        // The credentials map is populated by load() after this instance exists (a Credential is
        // an inner class binding this outer instance), so the live reference is shared, not copied.
        this.credentials = credentials;
        this.connectTimeout = connectTimeout;
        this.requestTimeout = requestTimeout;
        this.circuitBreakerThreshold = circuitBreakerThreshold;
        this.circuitBreakerOpenDuration = circuitBreakerOpenDuration;
    }

    /** Loads the outbound policy, failing fast on an unsupported credential {@code type}. */
    public static HttpOutbound load(AppConfig config) {
        Object node = config.navigate("tesseraql.http.outbound");
        boolean present = node instanceof Map<?, ?>;

        List<String> allowedHosts = new ArrayList<>();
        if (config.navigate("tesseraql.http.outbound.allowedHosts") instanceof List<?> hosts) {
            hosts.forEach(host -> allowedHosts.add(String.valueOf(host)));
        }

        Duration connectTimeout = config.getString("tesseraql.http.outbound.connectTimeout")
                .map(Durations::parse).orElse(DEFAULT_CONNECT_TIMEOUT);
        Duration requestTimeout = config.getString("tesseraql.http.outbound.requestTimeout")
                .map(Durations::parse).orElse(DEFAULT_REQUEST_TIMEOUT);
        int threshold = (int) config
                .getDouble("tesseraql.http.outbound.circuitBreaker.failureThreshold")
                .orElse(DEFAULT_CIRCUIT_BREAKER_THRESHOLD);
        Duration openDuration = config
                .getString("tesseraql.http.outbound.circuitBreaker.openDuration")
                .map(Durations::parse).orElse(DEFAULT_CIRCUIT_BREAKER_OPEN);

        Map<String, Credential> credentials = new LinkedHashMap<>();
        HttpOutbound loaded = new HttpOutbound(config, present, allowedHosts, credentials,
                connectTimeout, requestTimeout, threshold, openDuration);
        if (config.navigate("tesseraql.http.outbound.credentials") instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> {
                if (!(settings instanceof Map<?, ?> raw)) {
                    throw new TqlException(INVALID_CONFIG, "HTTP credential '" + name
                            + "' must be a map of settings");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((key, value) -> values.put(String.valueOf(key), value));
                Credential credential = loaded.new Credential(String.valueOf(name), values);
                String type = credential.type();
                if (!BEARER.equals(type) && !BASIC.equals(type) && !HEADER.equals(type)) {
                    throw new TqlException(INVALID_CONFIG, "HTTP credential '" + name
                            + "' has unsupported type '" + type + "' (expected bearer, basic, or"
                            + " header)");
                }
                credentials.put(String.valueOf(name), credential);
            });
        }
        return loaded;
    }

    /** Whether any {@code tesseraql.http.outbound} block is declared at all. */
    public boolean isEmpty() {
        return !present;
    }

    /** The declared egress allow-list (host patterns); empty means nothing is reachable. */
    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Whether the host is permitted by the allow-list. A pattern is either an exact host or a
     * {@code *.example.com} wildcard matching any sub-domain of {@code example.com} (matching is
     * case-insensitive). With no allow-list, nothing is reachable (deny by default).
     */
    public boolean isHostAllowed(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String candidate = host.toLowerCase(Locale.ROOT);
        for (String pattern : allowedHosts) {
            String normalized = pattern.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("*.")) {
                String suffix = normalized.substring(1);
                if (candidate.length() > suffix.length() && candidate.endsWith(suffix)) {
                    return true;
                }
            } else if (normalized.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int circuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public Duration circuitBreakerOpenDuration() {
        return circuitBreakerOpenDuration;
    }

    /** The named credential, or empty when not declared. */
    public Optional<Credential> credential(String name) {
        return Optional.ofNullable(credentials.get(name));
    }

    /** The named credential, or {@code TQL-BATCH-5308} so the call fails loudly. */
    public Credential requireCredential(String name) {
        Credential credential = credentials.get(name);
        if (credential == null) {
            throw new TqlException(UNKNOWN_CREDENTIAL, "HTTP credential '" + name
                    + "' is not configured under tesseraql.http.outbound.credentials");
        }
        return credential;
    }

    /** One configured credential; settings resolve placeholders (incl. secrets) on each read. */
    public final class Credential {

        private final String name;
        private final Map<String, Object> raw;

        private Credential(String name, Map<String, Object> raw) {
            this.name = name;
            this.raw = raw;
        }

        public String name() {
            return name;
        }

        /** The credential type: {@code bearer}, {@code basic}, or {@code header}. */
        public String type() {
            Object type = raw.get("type");
            if (type == null) {
                throw new TqlException(INVALID_CONFIG, "HTTP credential '" + name
                        + "' needs a type: (bearer, basic, or header)");
            }
            return String.valueOf(type);
        }

        /** A resolved setting value, or empty when not declared. */
        public Optional<String> setting(String key) {
            Object value = raw.get(key);
            return value == null
                    ? Optional.empty()
                    : Optional.of(config.resolve(String.valueOf(value)));
        }

        /** A resolved setting value, or {@code TQL-YAML-1103} when not declared. */
        public String require(String key) {
            return setting(key).orElseThrow(() -> new TqlException(INVALID_CONFIG,
                    "HTTP credential '" + name + "' needs a " + key + ": setting"));
        }
    }
}
