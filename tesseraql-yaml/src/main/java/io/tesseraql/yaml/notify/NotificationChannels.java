package io.tesseraql.yaml.notify;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The notification channels configured under {@code tesseraql.notifications.channels} (roadmap
 * Phase 20): named delivery targets — SMTP mail or HMAC-signed webhooks — that {@code notify:}
 * declarations reference by name.
 *
 * <p>Channel settings resolve their {@code ${...}} placeholders lazily, per read, so credentials
 * declared through the SecretResolver SPI ({@code ${secret.<provider>.<name>}}) are fetched at
 * delivery time, never at startup and never into logs or artifacts. A missing secret therefore
 * fails the delivery (retried and dead-lettered like any other failure) instead of the runtime.
 */
public final class NotificationChannels {

    /** TQL-YAML-1102: an invalid notification channel declaration (fail fast at startup). */
    public static final TqlErrorCode INVALID_CHANNEL = new TqlErrorCode(TqlDomain.YAML, 1102);
    /** TQL-BATCH-5301: a notification references a channel that is not configured. */
    public static final TqlErrorCode UNKNOWN_CHANNEL = new TqlErrorCode(TqlDomain.BATCH, 5301);

    /** The supported channel types. */
    public static final String MAIL = "mail";
    public static final String WEBHOOK = "webhook";

    private final AppConfig config;
    private final Map<String, Channel> channels;

    private NotificationChannels(AppConfig config, Map<String, Channel> channels) {
        this.config = config;
        this.channels = channels;
    }

    /** Loads the configured channels, failing fast on a missing or unsupported {@code type}. */
    public static NotificationChannels load(AppConfig config) {
        Object node = config.navigate("tesseraql.notifications.channels");
        Map<String, Channel> channels = new LinkedHashMap<>();
        NotificationChannels loaded = new NotificationChannels(config, channels);
        if (node instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> {
                if (!(settings instanceof Map<?, ?> raw)) {
                    throw new TqlException(INVALID_CHANNEL, "Notification channel '" + name
                            + "' must be a map of settings");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((key, value) -> values.put(String.valueOf(key), value));
                Channel channel = loaded.new Channel(String.valueOf(name), values);
                if (!MAIL.equals(channel.type()) && !WEBHOOK.equals(channel.type())) {
                    throw new TqlException(INVALID_CHANNEL, "Notification channel '" + name
                            + "' has unsupported type '" + channel.type()
                            + "' (expected mail or webhook)");
                }
                channels.put(String.valueOf(name), channel);
            });
        }
        return loaded;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public Set<String> names() {
        return channels.keySet();
    }

    public Optional<Channel> find(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    /** The named channel, or {@code TQL-BATCH-5301} so the delivery fails (and retries) loudly. */
    public Channel require(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            throw new TqlException(UNKNOWN_CHANNEL, "Notification channel '" + name
                    + "' is not configured under tesseraql.notifications.channels");
        }
        return channel;
    }

    /** One configured channel; settings resolve placeholders (incl. secrets) on each read. */
    public final class Channel {

        private final String name;
        private final Map<String, Object> raw;

        private Channel(String name, Map<String, Object> raw) {
            this.name = name;
            this.raw = raw;
        }

        public String name() {
            return name;
        }

        /** The channel type: {@code mail} or {@code webhook}. */
        public String type() {
            Object type = raw.get("type");
            if (type == null) {
                throw new TqlException(INVALID_CHANNEL,
                        "Notification channel '" + name + "' needs a type: (mail or webhook)");
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

        /**
         * A setting value without placeholder resolution — for values that are themselves
         * templates (a mail {@code subject} renders {@code [(${payload.x})]} inline, which must
         * not be mistaken for a config placeholder).
         */
        public Optional<String> raw(String key) {
            Object value = raw.get(key);
            return value == null ? Optional.empty() : Optional.of(String.valueOf(value));
        }

        /** A resolved setting value, or {@code TQL-YAML-1102} when not declared. */
        public String require(String key) {
            return setting(key).orElseThrow(() -> new TqlException(INVALID_CHANNEL,
                    "Notification channel '" + name + "' needs a " + key + ": setting"));
        }
    }
}
