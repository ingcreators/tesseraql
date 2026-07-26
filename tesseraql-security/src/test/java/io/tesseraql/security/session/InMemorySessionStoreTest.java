package io.tesseraql.security.session;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default session store expires its sessions.
 *
 * <p>It did not. {@code session()} was a bare map lookup, nothing ever pruned, and
 * {@code tesseraql.sessions.ttl} was read only on the JDBC branch — so on the default
 * configuration a session id stayed valid until the process restarted and the map grew one
 * principal per login forever. A stolen cookie outlived every control but an explicit logout.
 */
class InMemorySessionStoreTest {

    private static Principal principal(String subject) {
        return new Principal(subject, subject, subject, null, List.of(), List.of(), List.of(),
                Map.of());
    }

    @Test
    void rotateMintsAFreshIdAndCsrfAndKillsTheOldOne() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String old = store.create(principal("alice"));
        String oldCsrf = store.session(old).csrfToken();

        String fresh = store.rotate(old);

        assertThat(fresh).isNotNull().isNotEqualTo(old);
        // The old id is invalidated before the response leaves - no rotate-later window.
        assertThat(store.session(old)).isNull();
        SessionStore.Session rotated = store.session(fresh);
        assertThat(rotated.principal().subject()).isEqualTo("alice");
        // The CSRF token is session-bound state and rotates with it.
        assertThat(rotated.csrfToken()).isNotEqualTo(oldCsrf);
    }

    @Test
    void rotatingAnUnknownOrNullIdIsANoOpNotACrash() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));

        assertThat(store.rotate("unknown")).isNull();
        assertThat(store.rotate(null)).isNull();
    }

    @Test
    void aSessionPastItsTimeToLiveNoLongerResolves() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMillis(40));
        String id = store.create(principal("alice"));

        assertThat(store.session(id)).isNotNull();
        Thread.sleep(80);
        assertThat(store.session(id)).isNull();
    }

    @Test
    void anExpiredSessionIsAlsoDroppedFromTheSubjectsSessionList() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMillis(40));
        store.create(principal("alice"));
        Thread.sleep(80);

        // Resolving is what evicts it; the account page must not keep listing a dead session.
        assertThat(store.session("whatever")).isNull();
        store.create(principal("bob"));
        assertThat(store.sessionsFor("alice")).isEmpty();
    }

    @Test
    void withNoTimeToLiveTheHistoricalBehaviourIsKept() {
        // Embedders constructing the store directly keep a non-expiring one, so this change is
        // about the framework's own default rather than the class's only possible posture.
        InMemorySessionStore store = new InMemorySessionStore();
        String id = store.create(principal("alice"));

        assertThat(store.session(id)).isNotNull();
    }
}
