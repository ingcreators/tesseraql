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
    void metadataRoundTripsAndTheHandleIsNotTheCookieId() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String id = store.create(principal("alice"),
                new SessionStore.ClientInfo("Mozilla/5.0 (X11)", "203.0.113.7"));

        SessionStore.ActiveSession active = store.sessionsFor("alice").get(0);
        assertThat(active.userAgent()).isEqualTo("Mozilla/5.0 (X11)");
        assertThat(active.remoteAddr()).isEqualTo("203.0.113.7");
        assertThat(active.subject()).isEqualTo("alice");
        assertThat(active.lastSeenAt()).isNotNull();
        assertThat(active.handle()).isNotNull().isNotEqualTo(id);
    }

    @Test
    void invalidateByHandleIsSubjectScoped() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String alice = store.create(principal("alice"), SessionStore.ClientInfo.NONE);
        store.create(principal("bob"), SessionStore.ClientInfo.NONE);
        String handle = store.sessionsFor("alice").get(0).handle();

        // The wrong subject cannot name alice's session with her handle.
        store.invalidateByHandle("bob", handle);
        assertThat(store.session(alice)).isNotNull();

        store.invalidateByHandle("alice", handle);
        assertThat(store.session(alice)).isNull();
        assertThat(store.sessionsFor("bob")).hasSize(1);
    }

    @Test
    void theIdleWindowSlidesInsideTheAbsoluteTtl() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5), Duration.ofMillis(80));
        String id = store.create(principal("alice"), SessionStore.ClientInfo.NONE);

        // Within the idle window the session resolves; past it, unseen, it is gone even
        // though the absolute ttl has minutes left.
        assertThat(store.session(id)).isNotNull();
        Thread.sleep(160);
        assertThat(store.session(id)).isNull();
    }

    @Test
    void aTouchIsThrottledSoActivityIsNotPerRequestWork() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String id = store.create(principal("alice"), SessionStore.ClientInfo.NONE);
        java.time.Instant first = store.sessionsFor("alice").get(0).lastSeenAt();

        // Immediately after creation the throttle window is open: a touch is a no-op.
        store.touch(id);
        assertThat(store.sessionsFor("alice").get(0).lastSeenAt()).isEqualTo(first);
    }

    @Test
    void rotationCarriesTheDeviceAndTheCreationInstant() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String old = store.create(principal("alice"),
                new SessionStore.ClientInfo("Mozilla/5.0", "203.0.113.7"));
        SessionStore.ActiveSession before = store.sessionsFor("alice").get(0);

        String fresh = store.rotate(old);

        SessionStore.ActiveSession after = store.sessionsFor("alice").get(0);
        assertThat(fresh).isNotEqualTo(old);
        // Same person, same device, same login instant - only id, handle and CSRF are new.
        assertThat(after.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(after.remoteAddr()).isEqualTo("203.0.113.7");
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.handle()).isNotEqualTo(before.handle());
    }

    @Test
    void rotateMintsAFreshIdAndCsrfAndKillsTheOldOne() {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMinutes(5));
        String old = store.create(principal("alice"),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
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
        String id = store.create(principal("alice"),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);

        assertThat(store.session(id)).isNotNull();
        Thread.sleep(80);
        assertThat(store.session(id)).isNull();
    }

    @Test
    void anExpiredSessionIsAlsoDroppedFromTheSubjectsSessionList() throws Exception {
        InMemorySessionStore store = new InMemorySessionStore(
                SessionStore.DEFAULT_COOKIE_NAME, Duration.ofMillis(40));
        store.create(principal("alice"),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        Thread.sleep(80);

        // Resolving is what evicts it; the account page must not keep listing a dead session.
        assertThat(store.session("whatever")).isNull();
        store.create(principal("bob"), io.tesseraql.security.session.SessionStore.ClientInfo.NONE);
        assertThat(store.sessionsFor("alice")).isEmpty();
    }

    @Test
    void withNoTimeToLiveTheHistoricalBehaviourIsKept() {
        // Embedders constructing the store directly keep a non-expiring one, so this change is
        // about the framework's own default rather than the class's only possible posture.
        InMemorySessionStore store = new InMemorySessionStore();
        String id = store.create(principal("alice"),
                io.tesseraql.security.session.SessionStore.ClientInfo.NONE);

        assertThat(store.session(id)).isNotNull();
    }
}
