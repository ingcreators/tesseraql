package io.tesseraql.core.credential;

import java.time.Duration;
import java.util.Optional;

/**
 * One-time credential tokens (roadmap Phase 50, design in docs/credential-lifecycle.md):
 * password resets and invitations ride the same store. The raw token leaves through the
 * mailed link only — implementations persist a hash, enforce single use by check-and-set,
 * and refuse to issue while an unexpired, unused token of the same purpose exists for the
 * login (the cooldown that also caps mail volume).
 */
public interface CredentialTokenStore {

    /** Token purposes; a token consumed for the wrong purpose is simply invalid. */
    String RESET = "reset";

    String INVITE = "invite";

    /**
     * Issues a fresh token for the login, or empty while a live one exists (cooldown).
     * The returned value is the only copy of the raw token there will ever be.
     */
    Optional<String> issue(String loginId, String purpose, Duration timeToLive);

    /**
     * Consumes a raw token: the login it was issued for when it is known, unexpired,
     * unused, and purpose-matched — else empty. Winning is atomic; two racing confirms
     * cannot both succeed.
     */
    Optional<String> consume(String rawToken, String purpose);
}
