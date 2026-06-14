package io.tesseraql.security.apikey;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.apikey.ApiKeyConfig.ApiKeyClient;
import io.tesseraql.security.policy.PolicyEngine;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticates a service caller by its API key (design ch. 11.1). The presented key is hashed with
 * SHA-256 and compared, in constant time, against each active client's stored hash; the raw key is
 * never stored, logged, or echoed. A match resolves to that client's principal so existing
 * authorization policies apply; no match denies (deny-by-default).
 */
public final class ApiKeyAuthenticator {

    private final String header;
    private final List<Entry> entries;

    /** An active client's stored hash (lowercase-hex ASCII bytes) and the principal it grants. */
    private record Entry(byte[] hashAscii, Principal principal) {
    }

    public ApiKeyAuthenticator(ApiKeyConfig config) {
        this.header = config.header();
        List<Entry> active = new ArrayList<>();
        config.clients().forEach((id, client) -> {
            if (client.active() && client.secretHash() != null && !client.secretHash().isBlank()) {
                active.add(new Entry(
                        client.secretHash().trim().toLowerCase(Locale.ROOT)
                                .getBytes(StandardCharsets.US_ASCII),
                        principalOf(id, client)));
            }
        });
        this.entries = List.copyOf(active);
    }

    /** The request header carrying the key (for example {@code X-API-Key}). */
    public String header() {
        return header;
    }

    /** Authenticates a presented API key, or throws {@code TQL-SEC-4011}. */
    public Principal authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Missing API key");
        }
        byte[] presented = sha256Hex(presentedKey).getBytes(StandardCharsets.US_ASCII);
        Principal match = null;
        for (Entry entry : entries) {
            // Compare every entry (no early return) so the response time does not reveal which
            // client a key belongs to; MessageDigest.isEqual is itself constant-time.
            if (MessageDigest.isEqual(presented, entry.hashAscii())) {
                match = entry.principal();
            }
        }
        if (match == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid API key");
        }
        return match;
    }

    private static Principal principalOf(String id, ApiKeyClient client) {
        String subject = client.subject() == null || client.subject().isBlank()
                ? id
                : client.subject();
        return new Principal(subject, id, null, client.tenantId(),
                List.of(), client.roles(), client.permissions(),
                Map.of("api_key_client", id));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
