package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed, expiring share links for the documentation portal (Studio backlog F8, slice 3). A docs
 * route page is normally bearer-only; when the operator configures a signing secret
 * ({@code tesseraql.docs.share.secret}), an authenticated user can mint a link that opens that one
 * route's read-only reference <em>without</em> signing in. The link carries the route id, an expiry,
 * and an HMAC-SHA256 signature over both, so it cannot be retargeted or extended; the public share
 * route verifies the signature (constant-time) and the expiry before rendering.
 *
 * <p>Sharing is <strong>off</strong> until the secret is set — absent a secret, {@link #enabled()}
 * is false, no mint link is offered, and every link fails verification, so the portal stays
 * bearer-only by default. The secret is dedicated (not the JWT key) so docs sharing and request
 * authentication rotate independently and a leaked share secret cannot forge bearer tokens.
 */
final class ShareLinks {

    /** Binds a signature to this resource kind, so a route link can't be replayed as another. */
    private static final String LABEL = "route";
    private static final String SHARE_PATH = "/_tesseraql/docs/share/route";
    private static final long DEFAULT_TTL_SECONDS = 7 * 24 * 3600L;

    private final byte[] secret;
    private final long ttlSeconds;

    private ShareLinks(byte[] secret, long ttlSeconds) {
        this.secret = secret;
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * Builds the share-link signer from config: {@code tesseraql.docs.share.secret} (the HMAC key;
     * its presence enables sharing) and the optional {@code tesseraql.docs.share.ttl} link lifetime
     * (a duration like {@code 7d}, default 7 days). With no secret, sharing is disabled.
     */
    static ShareLinks from(AppConfig config) {
        String secret = config.getString("tesseraql.docs.share.secret").orElse(null);
        if (secret == null || secret.isBlank()) {
            return new ShareLinks(null, 0);
        }
        long ttl = config.getString("tesseraql.docs.share.ttl")
                .map(Durations::parse)
                .map(java.time.Duration::toSeconds)
                .filter(seconds -> seconds > 0)
                .orElse(DEFAULT_TTL_SECONDS);
        return new ShareLinks(secret.getBytes(StandardCharsets.UTF_8), ttl);
    }

    /**
     * A signer with an explicit secret and link lifetime (a test seam). A {@code null} secret yields
     * a disabled signer; the ttl is used as given (so a non-positive value mints already-expired
     * links for verifying the expiry path).
     */
    static ShareLinks of(String secret, long ttlSeconds) {
        return new ShareLinks(secret == null ? null : secret.getBytes(StandardCharsets.UTF_8),
                ttlSeconds);
    }

    /** Whether docs sharing is configured (a signing secret is present). */
    boolean enabled() {
        return secret != null;
    }

    /**
     * A signed, expiring share link for one route's reference, or {@code null} when sharing is
     * disabled or the id is missing. The returned value is an app-relative path the recipient opens
     * on the same host.
     */
    String mintRoute(String id) {
        if (!enabled() || id == null || id.isEmpty()) {
            return null;
        }
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        return SHARE_PATH + "?id=" + encode(id) + "&exp=" + exp + "&sig=" + sign(id, exp);
    }

    /**
     * Whether {@code (id, exp, sig)} is a currently valid share token: sharing is enabled, the
     * expiry has not passed, and the signature matches (compared in constant time). The id and exp
     * are the decoded query values the route binder supplies.
     */
    boolean verifyRoute(String id, String exp, String sig) {
        if (!enabled() || id == null || id.isEmpty() || exp == null || sig == null) {
            return false;
        }
        long expiry;
        try {
            expiry = Long.parseLong(exp.trim());
        } catch (NumberFormatException ex) {
            return false;
        }
        if (Instant.now().getEpochSecond() > expiry) {
            return false;
        }
        byte[] expected = sign(id, expiry).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    private String sign(String id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] raw = mac.doFinal(
                    (LABEL + "\n" + id + "\n" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign share link: " + ex.getMessage(), ex);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
