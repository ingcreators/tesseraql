package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.poll.JdbcPollConsumedStore;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One file, one replica (docs/audit-hardening.md Decision 4).
 *
 * <p>The store is what a poll source declaring {@code consumeOnce: true} arbitrates through. The
 * arbitration has to be the insert rather than a read: a "have I seen this?" check followed by an
 * import is check-then-act, and two replicas can both pass it. That is not a hypothetical — it is
 * precisely the shape Camel's non-eager idempotent consumer has, which is why the wiring specifies
 * {@code idempotentEager=true}.
 */
@Testcontainers
class PollConsumedStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static JdbcPollConsumedStore store(Duration retention) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        JdbcPollConsumedStore store = new JdbcPollConsumedStore(dataSource, retention);
        store.ensureSchema();
        return store;
    }

    @Test
    void onlyOneReplicaClaimsEachFile() {
        JdbcPollConsumedStore store = store(Duration.ofDays(30));

        assertThat(store.claim("orders.intake", "orders.csv-1024-1749513600000")).isTrue();
        // The second replica loses. Before this store existed, on sftp it simply imported the file
        // too — the remote read-lock strategy takes no lock at all.
        assertThat(store.claim("orders.intake", "orders.csv-1024-1749513600000")).isFalse();
    }

    /**
     * The key is name-size-modified, so a genuinely new file under a name already used is imported.
     *
     * <p>Camel's default key is the absolute path, which would have suppressed a partner's daily
     * {@code orders.csv} forever after the first one.
     */
    @Test
    void aNewFileUnderAReusedNameIsStillClaimed() {
        JdbcPollConsumedStore store = store(Duration.ofDays(30));

        assertThat(store.claim("daily.intake", "orders.csv-1024-1749513600000")).isTrue();
        assertThat(store.claim("daily.intake", "orders.csv-2048-1749600000000")).isTrue();
    }

    /** Two sources polling the same directory do not consume each other's files. */
    @Test
    void claimsAreScopedToTheirSource() {
        JdbcPollConsumedStore store = store(Duration.ofDays(30));

        assertThat(store.claim("source.a", "shared.csv-1-1")).isTrue();
        assertThat(store.claim("source.b", "shared.csv-1-1")).isTrue();
    }

    /**
     * A failed import releases its claim, so the file is not silently swallowed.
     *
     * <p>Camel calls {@code remove} when the exchange that took the claim failed.
     */
    @Test
    void releasingAClaimMakesTheFileConsumableAgain() {
        JdbcPollConsumedStore store = store(Duration.ofDays(30));

        assertThat(store.claim("retry.intake", "late.csv-1-1")).isTrue();
        assertThat(store.release("retry.intake", "late.csv-1-1")).isTrue();
        assertThat(store.claim("retry.intake", "late.csv-1-1")).isTrue();
    }

    /**
     * The retention window is the memory, and it is a declared key because it is user-visible: a
     * partner re-sending a byte-identical file is skipped inside the window and imported outside
     * it. Today, with no store at all, such a file is always re-imported.
     */
    @Test
    void aClaimOlderThanTheRetentionWindowStopsSuppressingTheFile() {
        JdbcPollConsumedStore forgetful = store(Duration.ZERO);

        assertThat(forgetful.claim("expiring.intake", "same.csv-1-1")).isTrue();
        // The next claim prunes anything older than now, which is the row just written.
        assertThat(forgetful.claim("expiring.intake", "same.csv-1-1")).isTrue();
    }
}
