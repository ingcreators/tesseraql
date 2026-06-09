package io.tesseraql.security.session;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.policy.PolicyEngine;

/**
 * Resolves a {@link Principal} from a browser session cookie (design ch. 11.1 {@code browser}).
 */
public final class BrowserAuthenticator {

    private final SessionStore sessions;

    public BrowserAuthenticator(SessionStore sessions) {
        this.sessions = sessions;
    }

    /** Authenticates using the value of the HTTP {@code Cookie} header. */
    public Principal authenticate(String cookieHeader) {
        String sessionId = Cookies.value(cookieHeader, sessions.cookieName());
        Principal principal = sessions.get(sessionId);
        if (principal == null) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "No active session");
        }
        return principal;
    }
}
