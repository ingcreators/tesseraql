package io.tesseraql.oauth;

import java.time.Instant;

/**
 * A stored authorization code (docs/token-issuance.md decision 2): single-use, short-lived, and
 * keyed by the SHA-256 hex of the code — the code itself is a bearer secret and never lands in
 * the store. The code challenge is kept without its method, because {@code S256} is the only
 * method this server accepts (decision 4); reconstruction reinstates it as a constant.
 *
 * @param resourceId the RFC 8707 {@code resource} the grant was made for — the audience boundary
 * @param actingRole the capacity a concurrent-role user selected at consent, or {@code null}
 */
public record IssuedCode(
        String codeHash,
        String clientId,
        String subject,
        String loginId,
        String resourceId,
        String actingRole,
        String codeChallenge,
        String redirectUri,
        Instant expiresAt) {
}
