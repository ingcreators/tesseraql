package io.tesseraql.yaml.webhook;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The inbound-webhook verifiers configured under {@code tesseraql.connectors.webhooks} (roadmap
 * Phase 26): named HMAC verifiers a {@code webhook} route references by {@code provider} to
 * authenticate a signed delivery and bound replay.
 *
 * <p>The signing secret resolves its {@code ${...}} placeholders lazily, per read, so a secret
 * declared through the SecretResolver SPI is fetched at verification time, never at startup and
 * never into logs or artifacts — mirroring {@code tesseraql.notifications.channels}. A verifier
 * signs over {@code <timestamp>.<body>} (the same scheme the Phase 20 outbound webhook uses), so
 * a TesseraQL app can both send and receive signed webhooks.
 */
public final class WebhookVerifiers {

    /** TQL-YAML-1105: an invalid or unknown {@code tesseraql.connectors.webhooks} verifier. */
    public static final TqlErrorCode INVALID_CONFIG = new TqlErrorCode(TqlDomain.YAML, 1105);

    /** Default header carrying the {@code sha256=<hex>} signature (matches HmacSignatures). */
    public static final String DEFAULT_SIGNATURE_HEADER = "X-TesseraQL-Signature";
    /** Default header carrying the epoch-seconds timestamp the signature covers. */
    public static final String DEFAULT_TIMESTAMP_HEADER = "X-TesseraQL-Timestamp";
    private static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

    private final AppConfig config;
    private final Map<String, Verifier> verifiers;

    private WebhookVerifiers(AppConfig config, Map<String, Verifier> verifiers) {
        this.config = config;
        // Populated by load() after this instance exists (Verifier is an inner class), so the live
        // reference is shared, not copied.
        this.verifiers = verifiers;
    }

    /** Loads the configured verifiers, failing fast on a malformed declaration. */
    public static WebhookVerifiers load(AppConfig config) {
        Map<String, Verifier> verifiers = new LinkedHashMap<>();
        WebhookVerifiers loaded = new WebhookVerifiers(config, verifiers);
        if (config.navigate("tesseraql.connectors.webhooks") instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> {
                if (!(settings instanceof Map<?, ?> raw)) {
                    throw new TqlException(INVALID_CONFIG, "Webhook verifier '" + name
                            + "' must be a map of settings");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((key, value) -> values.put(String.valueOf(key), value));
                verifiers.put(String.valueOf(name),
                        loaded.new Verifier(String.valueOf(name), values));
            });
        }
        return loaded;
    }

    public boolean isEmpty() {
        return verifiers.isEmpty();
    }

    public Optional<Verifier> find(String name) {
        return Optional.ofNullable(verifiers.get(name));
    }

    /** The named verifier, or {@code TQL-YAML-1105} so a misconfigured route fails fast at build. */
    public Verifier require(String name) {
        Verifier verifier = verifiers.get(name);
        if (verifier == null) {
            throw new TqlException(INVALID_CONFIG, "Webhook verifier '" + name
                    + "' is not configured under tesseraql.connectors.webhooks");
        }
        return verifier;
    }

    /** One configured verifier; the secret resolves placeholders on each read. */
    public final class Verifier {

        private final String name;
        private final Map<String, Object> raw;

        private Verifier(String name, Map<String, Object> raw) {
            this.name = name;
            this.raw = raw;
        }

        public String name() {
            return name;
        }

        /** The resolved signing secret, or {@code TQL-YAML-1105} when not declared. */
        public String secret() {
            Object value = raw.get("secret");
            if (value == null) {
                throw new TqlException(INVALID_CONFIG,
                        "Webhook verifier '" + name + "' needs a secret:");
            }
            return config.resolve(String.valueOf(value));
        }

        public String signatureHeader() {
            return setting("signatureHeader").orElse(DEFAULT_SIGNATURE_HEADER);
        }

        public String timestampHeader() {
            return setting("timestampHeader").orElse(DEFAULT_TIMESTAMP_HEADER);
        }

        /** The header whose value is the unique delivery id used as the replay key, if any. */
        public Optional<String> idHeader() {
            return setting("idHeader");
        }

        /** Deliveries whose timestamp is older/newer than this are rejected (default 5m). */
        public Duration tolerance() {
            return setting("tolerance").map(Durations::parse).orElse(DEFAULT_TOLERANCE);
        }

        private Optional<String> setting(String key) {
            Object value = raw.get(key);
            return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
        }
    }
}
