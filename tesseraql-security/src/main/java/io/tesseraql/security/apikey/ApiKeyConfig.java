package io.tesseraql.security.apikey;

import java.util.List;
import java.util.Map;

/**
 * API-key authentication settings for service callers (design ch. 11.1). Keys are declared in
 * configuration as a small, mostly-static set of machine clients; only a SHA-256 hash of each raw
 * key is stored (resolved through the secret SPI), never the key itself. Each client maps to an
 * explicit principal, so the same authorization policies apply as for any other caller.
 *
 * @param header  the request header carrying the key (default {@code X-API-Key})
 * @param clients clients keyed by a stable client id
 */
public record ApiKeyConfig(String header, Map<String, ApiKeyClient> clients) {

    public ApiKeyConfig {
        header = header == null || header.isBlank() ? "X-API-Key" : header;
        clients = clients == null ? Map.of() : Map.copyOf(clients);
    }

    /**
     * One API-key client and the principal it authenticates as.
     *
     * @param secretHash  hex SHA-256 of the raw key (never the raw key)
     * @param subject     the principal subject; defaults to the client id
     * @param tenantId    the principal tenant, bound from the key (not the request)
     * @param roles       granted roles
     * @param permissions granted permissions
     * @param active      whether the key is usable; a disabled key never authenticates
     */
    public record ApiKeyClient(
            String secretHash,
            String subject,
            String tenantId,
            List<String> roles,
            List<String> permissions,
            boolean active) {

        public ApiKeyClient {
            roles = roles == null ? List.of() : List.copyOf(roles);
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }
}
