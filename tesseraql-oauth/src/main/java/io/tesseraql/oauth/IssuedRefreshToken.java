package io.tesseraql.oauth;

import java.time.Instant;

/**
 * A stored refresh token (docs/token-issuance.md decision 2; stack-architecture.md decision 9):
 * stored so it can be revoked, rotated on use, and keyed by the SHA-256 hex of the token value —
 * the value itself is a bearer secret and never lands in the store.
 *
 * <p>Rotation keeps the spent row instead of deleting it, stamped {@code rotatedAt}: a rotated
 * token presented again is <em>reuse</em>, and reuse retires the whole {@code chainId} — the
 * distinction between "unknown token" and "already spent token" is what makes the detection
 * possible at all. {@code revokedAt} marks a chain retired by reuse, sign-out or the account
 * surface.
 */
public record IssuedRefreshToken(
        String tokenHash,
        String chainId,
        String clientId,
        String subject,
        String loginId,
        String resourceId,
        String actingRole,
        Instant issuedAt,
        Instant expiresAt,
        Instant rotatedAt,
        Instant revokedAt) {
}
