package io.tesseraql.oauth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The authorization server's persistence seam (docs/token-issuance.md decision 2): codes,
 * refresh tokens, clients and consents — and deliberately not access tokens, which are
 * stateless. The JDBC implementation rides the {@code security} migration component on the
 * framework datasource; tests drive the same contract in memory.
 */
public interface OAuthStore {

    Optional<RegisteredClient> findClient(String clientId);

    /** Registers or replaces; replacement is re-registration, which open DCR makes routine. */
    void saveClient(RegisteredClient client);

    /** Stamps {@code last_seen_at}, so unused registrations are findable by an operator. */
    void touchClient(String clientId, Instant lastSeenAt);

    void saveCode(IssuedCode code);

    /**
     * Removes and returns the code in one guarded step — the second concurrent consumer gets
     * empty, never a copy, which is what makes a code single-use under a race.
     */
    Optional<IssuedCode> consumeCode(String codeHash);

    void saveRefreshToken(IssuedRefreshToken token);

    Optional<IssuedRefreshToken> findRefreshToken(String tokenHash);

    /**
     * Stamps {@code rotated_at} iff the row is not already rotated, and says whether this call
     * won. The loser of a concurrent rotation race sees {@code false} and must treat the token
     * as reused — a guarded single-writer update, not a read-then-write, is the shape that
     * closes the TOCTOU race CXF's own provider shipped as CVE-2026-50631.
     */
    boolean markRotated(String tokenHash, Instant rotatedAt);

    /** Revokes every token in the chain — reuse detection, sign-out and the account surface. */
    void revokeChain(String chainId, Instant revokedAt);

    /** The live tokens behind a subject's "applications you have authorised" page (slice 8). */
    List<IssuedRefreshToken> refreshTokensFor(String clientId, String subject);

    void saveConsent(RecordedConsent consent);

    Optional<RecordedConsent> findConsent(String clientId, String subject, String resourceId);

    void deleteConsent(String clientId, String subject, String resourceId);

    /** Prunes expired codes and refresh tokens; called opportunistically, never on a hot path. */
    void deleteExpired(Instant now);
}
