package io.tesseraql.runtime;

import io.tesseraql.core.util.Durations;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signed, expiring share links for the documentation portal (Studio backlog F8, slice 3, extended).
 * A docs page is normally bearer-only; when the operator configures a signing secret
 * ({@code tesseraql.docs.share.secret}), an authenticated user can mint a link that opens that one
 * page's read-only view <em>without</em> signing in — a route's reference, a schema table, or the
 * coverage dashboard. The link carries the page's identity, an expiry, and an HMAC-SHA256 signature
 * over both (and a per-kind label, so a route link can't be replayed as a table link); the public
 * share route verifies the signature (constant-time) and the expiry before rendering.
 *
 * <p>Sharing is <strong>off</strong> until the secret is set — absent a secret, {@link #enabled()}
 * is false, no mint link is offered, and every link fails verification, so the portal stays
 * bearer-only by default. The secret is dedicated (not the JWT key) so docs sharing and request
 * authentication rotate independently and a leaked share secret cannot forge bearer tokens.
 */
final class ShareLinks {

    private static final String SHARE_BASE = "/_tesseraql/docs/share/";
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
     * A signed, expiring link to one route's reference, or {@code null} when sharing is disabled or
     * the id is missing. The returned value is an app-relative path the recipient opens on the same
     * host.
     */
    String mintRoute(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return mint("route", "route?id=" + encode(id), List.of(id));
    }

    /** Whether {@code (id, exp, sig)} is a currently valid route share token (see {@link #mintRoute}). */
    boolean verifyRoute(String id, String exp, String sig) {
        return id != null && !id.isEmpty() && verify("route", List.of(id), exp, sig);
    }

    /**
     * A signed, expiring link to one schema table's reference (datasource + name), or {@code null}
     * when sharing is disabled or either part is missing.
     */
    String mintTable(String datasource, String name) {
        if (datasource == null || datasource.isEmpty() || name == null || name.isEmpty()) {
            return null;
        }
        return mint("table", "table?ds=" + encode(datasource) + "&name=" + encode(name),
                List.of(datasource, name));
    }

    /** Whether {@code (datasource, name, exp, sig)} is a currently valid table share token. */
    boolean verifyTable(String datasource, String name, String exp, String sig) {
        return datasource != null && !datasource.isEmpty() && name != null && !name.isEmpty()
                && verify("table", List.of(datasource, name), exp, sig);
    }

    /** A signed, expiring link to the coverage dashboard, or {@code null} when sharing is disabled. */
    String mintCoverage() {
        return mint("coverage", "coverage", List.of());
    }

    /** Whether {@code (exp, sig)} is a currently valid coverage share token. */
    boolean verifyCoverage(String exp, String sig) {
        return verify("coverage", List.of(), exp, sig);
    }

    private String mint(String label, String query, List<String> parts) {
        if (!enabled()) {
            return null;
        }
        long exp = Instant.now().getEpochSecond() + ttlSeconds;
        String separator = query.indexOf('?') < 0 ? "?" : "&";
        return SHARE_BASE + query + separator + "exp=" + exp + "&sig=" + sign(label, parts, exp);
    }

    private boolean verify(String label, List<String> parts, String exp, String sig) {
        if (!enabled() || exp == null || sig == null) {
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
        byte[] expected = sign(label, parts, expiry).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, sig.getBytes(StandardCharsets.UTF_8));
    }

    /** HMAC-SHA256 over the kind label, the subject parts, and the expiry (each on its own line). */
    private String sign(String label, List<String> parts, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            StringBuilder subject = new StringBuilder(label);
            for (String part : parts) {
                subject.append('\n').append(part);
            }
            subject.append('\n').append(exp);
            byte[] raw = mac.doFinal(subject.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to sign share link: " + ex.getMessage(), ex);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
