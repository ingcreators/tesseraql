package io.tesseraql.yaml.messaging;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The messaging channels configured under {@code tesseraql.messaging.channels} (roadmap Phase 27):
 * named transports a {@code publish:} block emits to and a {@code queue-consume} route subscribes
 * from. A channel carries no business meaning beyond its transport and that transport's settings —
 * the {@code topic} is declared on the route, not here — so the same channel multiplexes many
 * topics.
 *
 * <p>Two built-in transports share the same durable {@code tql_event} table on the app's main
 * datasource: {@code pg-notify} adds PostgreSQL {@code LISTEN}/{@code NOTIFY} as a low-latency wake
 * signal (PostgreSQL only), and {@code db-poll} polls the table on the backstop interval (every
 * dialect — the portable floor, also the choice on PostgreSQL behind a transaction-pooling proxy
 * that breaks {@code LISTEN}). Both give the same at-least-once, idempotent delivery; they differ
 * only in latency. Brokers (Kafka, JMS) arrive as opt-in leaf modules that register additional
 * transports; the {@code publish:}/{@code consume:} YAML is identical across transports, so an app
 * moves between them by changing only this config.
 *
 * <p>Settings resolve their {@code ${...}} placeholders lazily, per read, so a secret declared
 * through the SecretResolver SPI is fetched at use time, never at startup — mirroring
 * {@code tesseraql.notifications.channels} and {@code tesseraql.connectors.webhooks}.
 */
public final class MessagingChannels {

    /** The built-in PostgreSQL transport: a durable table plus low-latency LISTEN/NOTIFY. */
    public static final String PG_NOTIFY = "pg-notify";
    /** The built-in portable transport: the same durable table, polled on the backstop interval. */
    public static final String DB_POLL = "db-poll";
    private static final java.util.Set<String> BUILT_IN_TRANSPORTS = java.util.Set.of(PG_NOTIFY,
            DB_POLL);

    /** TQL-YAML-1106: an invalid or unknown {@code tesseraql.messaging.channels} declaration. */
    public static final TqlErrorCode INVALID_CONFIG = new TqlErrorCode(TqlDomain.YAML, 1106);

    private final AppConfig config;
    private final Map<String, Channel> channels;

    private MessagingChannels(AppConfig config, Map<String, Channel> channels) {
        this.config = config;
        this.channels = channels;
    }

    /** Loads the configured channels, failing fast on a malformed declaration. */
    public static MessagingChannels load(AppConfig config) {
        Map<String, Channel> channels = new LinkedHashMap<>();
        MessagingChannels loaded = new MessagingChannels(config, channels);
        if (config.navigate("tesseraql.messaging.channels") instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> {
                if (!(settings instanceof Map<?, ?> raw)) {
                    throw new TqlException(INVALID_CONFIG, "Messaging channel '" + name
                            + "' must be a map of settings");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((key, value) -> values.put(String.valueOf(key), value));
                Channel channel = loaded.new Channel(String.valueOf(name), values);
                if (!BUILT_IN_TRANSPORTS.contains(channel.transport())) {
                    // Only the built-in transports ship in the core runtime; a broker transport
                    // is contributed by an opt-in leaf module, so an unknown one is a typo here.
                    throw new TqlException(INVALID_CONFIG, "Messaging channel '" + name
                            + "' declares unknown transport '" + channel.transport()
                            + "' (built-in transports: '" + PG_NOTIFY + "', '" + DB_POLL + "')");
                }
                channels.put(String.valueOf(name), channel);
            });
        }
        return loaded;
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    public Optional<Channel> find(String name) {
        return Optional.ofNullable(channels.get(name));
    }

    /** The named channel, or {@code TQL-YAML-1106} so a misconfigured route fails fast at build. */
    public Channel require(String name) {
        Channel channel = channels.get(name);
        if (channel == null) {
            throw new TqlException(INVALID_CONFIG, "Messaging channel '" + name
                    + "' is not configured under tesseraql.messaging.channels");
        }
        return channel;
    }

    /** One configured channel; settings resolve placeholders on each read. */
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

        /** The transport id, defaulting to the built-in {@link #PG_NOTIFY}. */
        public String transport() {
            return setting("transport").orElse(PG_NOTIFY);
        }

        public Optional<String> setting(String key) {
            Object value = raw.get(key);
            return value == null
                    ? Optional.empty()
                    : Optional.of(config.resolve(String.valueOf(value)));
        }
    }
}
