package io.tesseraql.yaml.connectors;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.http.HttpOutbound;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The inbound-polling policy configured under {@code tesseraql.connectors.poll} (roadmap
 * Phase 26): the host allow-list and named credentials a {@code poll:} trigger draws on to reach a
 * remote SFTP/FTPS source.
 *
 * <p>Reaching a remote host is deny-by-default: a poll trigger may only target a host in
 * {@code allowedHosts}, so a job can never connect to an arbitrary server. Credential settings
 * resolve their {@code ${...}} placeholders lazily, per read, so secrets declared through the
 * SecretResolver SPI are fetched when the consumer starts, never into logs or artifacts —
 * mirroring {@link HttpOutbound} for outbound HTTP.
 */
public final class PollConnectors {

    /** TQL-YAML-1104: an invalid {@code tesseraql.connectors.poll} declaration (fail fast). */
    public static final TqlErrorCode INVALID_CONFIG = new TqlErrorCode(TqlDomain.YAML, 1104);
    /** TQL-BATCH-5310: a poll trigger references an unconfigured credential. */
    public static final TqlErrorCode UNKNOWN_CREDENTIAL = new TqlErrorCode(TqlDomain.BATCH, 5310);

    private final AppConfig config;
    private final boolean present;
    private final List<String> allowedHosts;
    private final Map<String, Credential> credentials;

    private PollConnectors(AppConfig config, boolean present, List<String> allowedHosts,
            Map<String, Credential> credentials) {
        this.config = config;
        this.present = present;
        this.allowedHosts = List.copyOf(allowedHosts);
        // Populated by load() after this instance exists (Credential is an inner class), so the
        // live reference is shared, not copied.
        this.credentials = credentials;
    }

    /** Loads the polling policy, failing fast on a malformed credential declaration. */
    public static PollConnectors load(AppConfig config) {
        boolean present = config.navigate("tesseraql.connectors.poll") instanceof Map<?, ?>;
        List<String> allowedHosts = new ArrayList<>();
        if (config.navigate("tesseraql.connectors.poll.allowedHosts") instanceof List<?> hosts) {
            hosts.forEach(host -> allowedHosts.add(String.valueOf(host)));
        }
        Map<String, Credential> credentials = new LinkedHashMap<>();
        PollConnectors loaded = new PollConnectors(config, present, allowedHosts, credentials);
        if (config
                .navigate("tesseraql.connectors.poll.credentials") instanceof Map<?, ?> declared) {
            declared.forEach((name, settings) -> {
                if (!(settings instanceof Map<?, ?> raw)) {
                    throw new TqlException(INVALID_CONFIG, "Poll credential '" + name
                            + "' must be a map of settings");
                }
                Map<String, Object> values = new LinkedHashMap<>();
                raw.forEach((key, value) -> values.put(String.valueOf(key), value));
                credentials.put(String.valueOf(name), loaded.new Credential(String.valueOf(name),
                        values));
            });
        }
        return loaded;
    }

    /** Whether any {@code tesseraql.connectors.poll} block is declared at all. */
    public boolean isEmpty() {
        return !present;
    }

    public List<String> allowedHosts() {
        return allowedHosts;
    }

    /**
     * Whether the remote host is permitted by the allow-list (exact or {@code *.wildcard}, the same
     * deny-by-default rule outbound HTTP uses). With no allow-list, nothing is reachable.
     */
    public boolean isHostAllowed(String host) {
        return HttpOutbound.hostAllowed(allowedHosts, host);
    }

    /** The named credential, or empty when not declared. */
    public Optional<Credential> credential(String name) {
        return Optional.ofNullable(credentials.get(name));
    }

    /** The named credential, or {@code TQL-BATCH-5310} so the consumer fails loudly. */
    public Credential requireCredential(String name) {
        Credential credential = credentials.get(name);
        if (credential == null) {
            throw new TqlException(UNKNOWN_CREDENTIAL, "Poll credential '" + name
                    + "' is not configured under tesseraql.connectors.poll.credentials");
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

        /** A resolved setting value, or empty when not declared. */
        public Optional<String> setting(String key) {
            Object value = raw.get(key);
            return value == null
                    ? Optional.empty()
                    : Optional.of(config.resolve(String.valueOf(value)));
        }

        /** A resolved setting value, or {@code TQL-YAML-1104} when not declared. */
        public String require(String key) {
            return setting(key).orElseThrow(() -> new TqlException(INVALID_CONFIG,
                    "Poll credential '" + name + "' needs a " + key + ": setting"));
        }
    }
}
