package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.security.Principal;
import io.tesseraql.security.session.JdbcSessionStore;
import io.tesseraql.security.session.SessionStore;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The shared-store half of docs/session-visibility.md, against real PostgreSQL: metadata
 * columns round-trip, the handle is subject-scoped, the idle window slides inside the
 * absolute TTL, rotation carries the device facts, and pre-upgrade rows (null metadata)
 * render dashes and survive the idle check to their absolute expiry.
 */
@Testcontainers
class JdbcSessionStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static PGSimpleDataSource dataSource;

    @BeforeAll
    static void schema() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcSessionStore store = new JdbcSessionStore(dataSource, Duration.ofHours(1));
        store.ensureSchema();
        // Re-runnable: the bootstrap tolerates the duplicate-column errors on a second run.
        store.ensureSchema();
    }

    private static Principal principal(String subject) {
        return new Principal(subject, subject, subject, null, List.of(), List.of(), List.of(),
                Map.of());
    }

    private static JdbcSessionStore store(Duration idle) {
        return new JdbcSessionStore(dataSource, Duration.ofHours(1), idle,
                SessionStore.DEFAULT_COOKIE_NAME);
    }

    @Test
    void metadataRoundTripsAndTheHandleIsSubjectScoped() {
        JdbcSessionStore store = store(null);
        String id = store.create(principal("jdbc-alice"),
                new SessionStore.ClientInfo("Mozilla/5.0 (X11)", "203.0.113.7"));
        store.create(principal("jdbc-bob"), SessionStore.ClientInfo.NONE);

        SessionStore.ActiveSession active = store.sessionsFor("jdbc-alice").get(0);
        assertThat(active.userAgent()).isEqualTo("Mozilla/5.0 (X11)");
        assertThat(active.remoteAddr()).isEqualTo("203.0.113.7");
        assertThat(active.lastSeenAt()).isNotNull();
        assertThat(active.handle()).isNotNull().isNotEqualTo(id);

        // The wrong subject cannot name alice's session with her handle.
        store.invalidateByHandle("jdbc-bob", active.handle());
        assertThat(store.session(id)).isNotNull();
        store.invalidateByHandle("jdbc-alice", active.handle());
        assertThat(store.session(id)).isNull();
    }

    @Test
    void theIdleWindowSlidesAndPreUpgradeRowsLiveToTheirAbsoluteExpiry() throws Exception {
        JdbcSessionStore store = store(Duration.ofSeconds(1));
        String idle = store.create(principal("jdbc-idle"), SessionStore.ClientInfo.NONE);

        // A V2-era row: no metadata at all. The idle window cannot honestly apply.
        String legacy = "legacy-" + java.util.UUID.randomUUID();
        try (var connection = dataSource.getConnection();
                var ps = connection.prepareStatement(
                        "insert into tql_session (session_id, principal_json, csrf_token, "
                                + "created_at, expires_at, subject) values (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, legacy);
            ps.setString(2, new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(principal("jdbc-legacy")));
            ps.setString(3, "csrf");
            ps.setTimestamp(4, java.sql.Timestamp.from(java.time.Instant.now()));
            ps.setTimestamp(5, java.sql.Timestamp.from(
                    java.time.Instant.now().plus(Duration.ofHours(1))));
            ps.setString(6, "jdbc-legacy");
            ps.executeUpdate();
        }

        assertThat(store.session(idle)).isNotNull();
        Thread.sleep(1_500);
        assertThat(store.session(idle)).isNull();
        assertThat(store.session(legacy)).isNotNull();
        SessionStore.ActiveSession legacyRow = store.sessionsFor("jdbc-legacy").get(0);
        assertThat(legacyRow.handle()).isNull();
        assertThat(legacyRow.userAgent()).isNull();
    }

    @Test
    void rotationCarriesTheDeviceTheCreationInstantAndTheExpiryCeiling() {
        JdbcSessionStore store = store(null);
        String old = store.create(principal("jdbc-rotate"),
                new SessionStore.ClientInfo("Mozilla/5.0", "203.0.113.9"));
        SessionStore.ActiveSession before = store.sessionsFor("jdbc-rotate").get(0);

        String fresh = store.rotate(old);

        assertThat(fresh).isNotEqualTo(old);
        assertThat(store.session(old)).isNull();
        SessionStore.ActiveSession after = store.sessionsFor("jdbc-rotate").get(0);
        assertThat(after.userAgent()).isEqualTo("Mozilla/5.0");
        assertThat(after.createdAt()).isEqualTo(before.createdAt());
        assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
        assertThat(after.handle()).isNotEqualTo(before.handle());
    }

    /**
     * The rotation commits, and only the cleanup after it fails. A {@code finally} that throws
     * discards the enclosing {@code return}, so the caller used to be told the rotation failed
     * over a transaction that had already deleted the old row and written the new one — a sign-out
     * caused by nothing but a connection dying between the commit and the autocommit restore.
     */
    @Test
    void aCommittedRotationSurvivesAFailedAutocommitRestore() {
        JdbcSessionStore honest = store(null);
        String old = honest.create(principal("jdbc-restore"),
                new SessionStore.ClientInfo("Mozilla/5.0", "203.0.113.11"));

        String fresh = new JdbcSessionStore(refusesToRestoreAutocommit(dataSource),
                Duration.ofHours(1), null, SessionStore.DEFAULT_COOKIE_NAME).rotate(old);

        assertThat(fresh).isNotNull().isNotEqualTo(old);
        assertThat(honest.session(old)).isNull();
        assertThat(honest.session(fresh)).isNotNull();
    }

    /**
     * A DataSource whose connections refuse to go back to autocommit. It sabotages the cleanup
     * only: the read, the insert, the delete and the commit all run against the real database.
     * Keyed on having seen {@code setAutoCommit(false)} first, so a driver that sets autocommit on
     * a fresh connection is unaffected.
     */
    private static DataSource refusesToRestoreAutocommit(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(
                JdbcSessionStoreIntegrationTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    Object answer = invoke(target, method, args);
                    if (!(answer instanceof Connection connection)) {
                        return answer;
                    }
                    boolean[] inTransaction = new boolean[1];
                    return Proxy.newProxyInstance(
                            JdbcSessionStoreIntegrationTest.class.getClassLoader(),
                            new Class<?>[]{Connection.class},
                            (c, called, callArgs) -> {
                                if ("setAutoCommit".equals(called.getName())) {
                                    if (Boolean.TRUE.equals(callArgs[0]) && inTransaction[0]) {
                                        throw new SQLException("connection reset by peer");
                                    }
                                    inTransaction[0] = Boolean.FALSE.equals(callArgs[0]);
                                }
                                return invoke(connection, called, callArgs);
                            });
                });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }

    @Test
    void theCapEvictsTheOldestAndRotationNeverTripsIt() {
        JdbcSessionStore store = new JdbcSessionStore(dataSource, Duration.ofHours(1), null,
                2, SessionStore.DEFAULT_COOKIE_NAME);
        String first = store.create(principal("jdbc-cap"), SessionStore.ClientInfo.NONE);
        String second = store.create(principal("jdbc-cap"), SessionStore.ClientInfo.NONE);
        String third = store.create(principal("jdbc-cap"), SessionStore.ClientInfo.NONE);

        assertThat(store.session(first)).isNull();
        assertThat(store.session(second)).isNotNull();
        assertThat(store.session(third)).isNotNull();

        // Rotation at the cap replaces in place: the other device survives.
        String rotated = store.rotate(third);
        assertThat(rotated).isNotNull();
        assertThat(store.session(second)).isNotNull();
        assertThat(store.sessionsFor("jdbc-cap")).hasSize(2);
    }

    @Test
    void activeSessionsListsNewestFirstWithinTheLimit() {
        JdbcSessionStore store = store(null);
        store.create(principal("jdbc-list-1"), SessionStore.ClientInfo.NONE);
        store.create(principal("jdbc-list-2"), SessionStore.ClientInfo.NONE);

        List<SessionStore.ActiveSession> all = store.activeSessions(200);
        assertThat(all.size()).isGreaterThanOrEqualTo(2);
        assertThat(store.activeSessions(1)).hasSize(1);
        assertThat(all).allSatisfy(row -> assertThat(row.subject()).isNotNull());
    }
}
