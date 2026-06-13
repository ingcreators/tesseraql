package io.tesseraql.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Resolving a session's CSRF token straight from a {@code Cookie} header, so a request pipeline can
 * publish the token (the {@code <meta name="csrf-token">} convention) without parsing cookies.
 */
class SessionStoreCsrfTest {

    private final SessionStore sessions = new InMemorySessionStore();

    @Test
    void resolvesTheTokenFromTheCookieHeader() {
        String sid = sessions.create(principal());
        String expected = sessions.csrfToken(sid);

        String header = "other=1; " + sessions.cookieName() + "=" + sid + "; trailing=2";
        assertThat(sessions.csrfTokenFromCookie(header)).isEqualTo(expected);
    }

    @Test
    void returnsNullWhenNoSessionResolves() {
        assertThat(sessions.csrfTokenFromCookie(null)).isNull();
        assertThat(sessions.csrfTokenFromCookie("unrelated=value")).isNull();
        assertThat(sessions.csrfTokenFromCookie(sessions.cookieName() + "=unknown")).isNull();
    }

    private static Principal principal() {
        return new Principal("u1", "sato", "Sato", null, List.of(), List.of(), List.of(), Map.of());
    }
}
