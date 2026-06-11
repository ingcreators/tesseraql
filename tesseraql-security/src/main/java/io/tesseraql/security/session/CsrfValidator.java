package io.tesseraql.security.session;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.policy.PolicyEngine;

/**
 * Validates the CSRF token of a state-changing browser request against the session token
 * (design ch. 11.3, 20.11). The token is supplied in a request header (default {@code X-CSRF-Token}).
 */
public final class CsrfValidator {

    private final SessionStore sessions;

    public CsrfValidator(SessionStore sessions) {
        this.sessions = sessions;
    }

    /** Validates the CSRF token, throwing on a missing session or mismatched token. */
    public void validate(String cookieHeader, String csrfToken) {
        String sessionId = Cookies.value(cookieHeader, sessions.cookieName());
        SessionStore.Session session = sessions.session(sessionId);
        if (session == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "No active session for CSRF check");
        }
        if (csrfToken == null || !session.csrfToken().equals(csrfToken)) {
            throw new TqlException(PolicyEngine.CSRF_FAILED, "Invalid CSRF token");
        }
    }
}
