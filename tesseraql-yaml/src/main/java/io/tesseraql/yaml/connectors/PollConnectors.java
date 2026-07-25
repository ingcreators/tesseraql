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
 *
 * <p>An optional {@code knownHostsFile} pins the SSH host keys SFTP sources are allowed to
 * present (an OpenSSH known-hosts file, resolved against the app home). When set, every SFTP
 * consumer verifies the server's host key against it; when unset, host keys are not checked and
 * lint nudges with {@code TQL-SEC-4084}.
 */
public final class PollConnectors {

    /** TQL-YAML-1104: an invalid {@code tesseraql.connectors.poll} declaration (fail fast). */
    public static final TqlErrorCode INVALID_CONFIG = new TqlErrorCode(TqlDomain.YAML, 1104);
    /** TQL-BATCH-5310: a poll trigger references an unconfigured credential. */
    public static final TqlErrorCode UNKNOWN_CREDENTIAL = new TqlErrorCode(TqlDomain.BATCH, 5310);

    private final AppConfig config;
    private final boolean present;
    private final List<String> allowedHosts;
    private final List<String> allowedPaths;
    private final String knownHostsFile;
    private final TrustStore trustStore;
    private final Map<String, Credential> credentials;

    /**
     * The CA bundle an FTPS server's certificate is validated against — the FTPS counterpart of
     * {@code knownHostsFile}. Without it the client accepts any in-date certificate from any
     * host, so the TLS handshake proves nothing about who is on the other end.
     *
     * @param file     keystore path, relative to the app home or absolute
     * @param password keystore password, resolved through the secret resolver like any other
     */
    public record TrustStore(String file, String password) {
    }

    private PollConnectors(AppConfig config, boolean present, List<String> allowedHosts,
            List<String> allowedPaths, String knownHostsFile, TrustStore trustStore,
            Map<String, Credential> credentials) {
        this.config = config;
        this.present = present;
        this.allowedHosts = List.copyOf(allowedHosts);
        this.allowedPaths = List.copyOf(allowedPaths);
        this.knownHostsFile = knownHostsFile;
        this.trustStore = trustStore;
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
        String knownHostsFile = config.getString("tesseraql.connectors.poll.knownHostsFile")
                .filter(value -> !value.isBlank())
                .orElse(null);
        List<String> allowedPaths = new ArrayList<>();
        if (config.navigate("tesseraql.connectors.poll.allowedPaths") instanceof List<?> paths) {
            paths.forEach(path -> allowedPaths.add(String.valueOf(path)));
        }
        TrustStore trustStore = loadTrustStore(config);
        Map<String, Credential> credentials = new LinkedHashMap<>();
        PollConnectors loaded = new PollConnectors(config, present, allowedHosts, allowedPaths,
                knownHostsFile, trustStore, credentials);
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
     * The OpenSSH known-hosts file SFTP host keys are verified against (a path relative to the
     * app home, or absolute), or empty when host-key checking is left off.
     */
    public Optional<String> knownHostsFile() {
        return Optional.ofNullable(knownHostsFile);
    }

    /**
     * The trust store an FTPS server's certificate chain is validated against, or empty when the
     * app declares none — in which case the endpoint refuses to connect rather than trusting
     * whatever certificate it is offered.
     */
    public Optional<TrustStore> trustStore() {
        return Optional.ofNullable(trustStore);
    }

    /**
     * Resolves a {@code source: local} path against the declared roots
     * ({@code tesseraql.connectors.poll.allowedPaths}), the same rule
     * {@code FileScopes} applies to every other filesystem surface: resolve under a root,
     * normalize, then re-check the prefix so {@code ..} cannot climb out.
     *
     * <p>Deny by default, like the host allow-list. Without roots a local poll job is a
     * read-and-move primitive anywhere the process user can reach — and it *moves* what it
     * reads, so a mis-pointed path silently relocates a live directory's contents into
     * {@code .done}.
     *
     * @param appHome the app home a relative path resolves against
     * @param path    the declared {@code path:}
     * @return the anchored absolute path
     * @throws TqlException when no root is declared, or the path escapes every root
     */
    public java.nio.file.Path requireAllowedPath(java.nio.file.Path appHome, String path) {
        if (path == null || path.isBlank()) {
            throw new TqlException(INVALID_CONFIG, "A local poll source needs a path:");
        }
        if (allowedPaths.isEmpty()) {
            throw new TqlException(INVALID_CONFIG,
                    "A local poll source needs tesseraql.connectors.poll.allowedPaths: without a"
                            + " declared root the job can read and move files anywhere the"
                            + " process can reach");
        }
        java.nio.file.Path candidate = appHome.resolve(path).normalize().toAbsolutePath();
        for (String declared : allowedPaths) {
            java.nio.file.Path root = appHome.resolve(declared).normalize().toAbsolutePath();
            if (candidate.startsWith(root)) {
                return candidate;
            }
        }
        throw new TqlException(INVALID_CONFIG, "Local poll path '" + path + "' resolves outside"
                + " every tesseraql.connectors.poll.allowedPaths root");
    }

    /** The declared local poll roots, empty when the app declares none. */
    public List<String> allowedPaths() {
        return allowedPaths;
    }

    /**
     * Whether the remote host is permitted by the allow-list (exact or {@code *.wildcard}, the same
     * deny-by-default rule outbound HTTP uses). With no allow-list, nothing is reachable.
     */
    public boolean isHostAllowed(String host) {
        return HttpOutbound.hostAllowed(allowedHosts, host);
    }

    /** Reads and validates {@code tesseraql.connectors.poll.trustStore}. */
    private static TrustStore loadTrustStore(AppConfig config) {
        Object node = config.navigate("tesseraql.connectors.poll.trustStore");
        if (node == null) {
            return null;
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw new TqlException(INVALID_CONFIG,
                    "tesseraql.connectors.poll.trustStore must be a mapping with file: and"
                            + " password:");
        }
        String file = config.getString("tesseraql.connectors.poll.trustStore.file")
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> new TqlException(INVALID_CONFIG,
                        "tesseraql.connectors.poll.trustStore needs a file:"));
        String password = config.getString("tesseraql.connectors.poll.trustStore.password")
                .orElse(null);
        return new TrustStore(file, password);
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
