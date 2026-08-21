package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.oauth.IssuedCode;
import io.tesseraql.oauth.IssuedRefreshToken;
import io.tesseraql.oauth.JdbcOAuthStore;
import io.tesseraql.oauth.RecordedConsent;
import io.tesseraql.oauth.RegisteredClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The authorization server's JDBC store against a real database (docs/token-issuance.md
 * slice 2), on the V5 tables of the {@code security} migration component the host migrates.
 * The unit suite proves the provider against the store contract; this proves the JDBC
 * implementation keeps that contract — single-use consume, single-winner rotation, chain
 * revocation — as database properties.
 */
@Testcontainers
class JdbcOAuthStoreIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static JdbcOAuthStore store;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        FrameworkMigrations.migrateSecurity(dataSource);
        store = new JdbcOAuthStore((DataSource) dataSource);
    }

    @Test
    void aClientRoundTripsAndReRegistrationReplaces() {
        Instant now = Instant.now();
        store.saveClient(new RegisteredClient("codex", "hash-1",
                List.of("http://127.0.0.1:49681/callback/a"), "Codex CLI", "{\"n\":1}", now,
                null));
        // Re-registration with a new ephemeral port replaces — churn is by design.
        store.saveClient(new RegisteredClient("codex", "hash-2",
                List.of("http://127.0.0.1:50123/callback/b",
                        "http://127.0.0.1:50124/callback/c"),
                "Codex CLI", null, now, now));

        assertThat(store.findClient("codex")).hasValueSatisfying(client -> {
            assertThat(client.secretHash()).isEqualTo("hash-2");
            assertThat(client.redirectUris()).containsExactly(
                    "http://127.0.0.1:50123/callback/b", "http://127.0.0.1:50124/callback/c");
        });

        store.touchClient("codex", now.plusSeconds(60));
        assertThat(store.findClient("codex").orElseThrow().lastSeenAt())
                .isAfterOrEqualTo(now.plusSeconds(59));
    }

    @Test
    void aCodeIsConsumedExactlyOnce() {
        Instant now = Instant.now();
        String hash = UUID.randomUUID().toString();
        store.saveCode(new IssuedCode(hash, "codex", "u-1", "eve",
                "https://stack.example.com/orders", "approver", "challenge", "http://cb",
                now.plusSeconds(120)));

        assertThat(store.consumeCode(hash)).hasValueSatisfying(code -> {
            assertThat(code.resourceId()).isEqualTo("https://stack.example.com/orders");
            assertThat(code.actingRole()).isEqualTo("approver");
        });
        assertThat(store.consumeCode(hash)).isEmpty();
    }

    @Test
    void rotationHasExactlyOneWinnerAndReuseRetiresTheChain() {
        Instant now = Instant.now();
        String chain = UUID.randomUUID().toString();
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        store.saveRefreshToken(token(first, chain, now));

        assertThat(store.markRotated(first, now)).isTrue();
        // The losing side of a concurrent rotation sees false, which the provider reads as reuse.
        assertThat(store.markRotated(first, now)).isFalse();

        store.saveRefreshToken(token(second, chain, now));
        store.revokeChain(chain, now);

        assertThat(store.findRefreshToken(second).orElseThrow().revokedAt()).isNotNull();
        assertThat(store.refreshTokensFor("codex", "u-1")).isEmpty();
    }

    @Test
    void theSubjectListingShowsOnlyLiveTokens() {
        Instant now = Instant.now();
        String live = UUID.randomUUID().toString();
        String rotated = UUID.randomUUID().toString();
        store.saveRefreshToken(token(live, UUID.randomUUID().toString(), now));
        store.saveRefreshToken(token(rotated, UUID.randomUUID().toString(), now));
        store.markRotated(rotated, now);

        List<IssuedRefreshToken> listed = store.refreshTokensFor("codex", "u-1");

        assertThat(listed).extracting(IssuedRefreshToken::tokenHash).contains(live)
                .doesNotContain(rotated);
    }

    @Test
    void consentIsPerClientAndPerResourceAndReplaceable() {
        Instant now = Instant.now();
        store.saveConsent(new RecordedConsent("codex", "u-1",
                "https://stack.example.com/orders", null, now));
        store.saveConsent(new RecordedConsent("codex", "u-1",
                "https://stack.example.com/orders", "approver", now.plusSeconds(5)));

        assertThat(store.findConsent("codex", "u-1", "https://stack.example.com/orders"))
                .hasValueSatisfying(
                        consent -> assertThat(consent.actingRole()).isEqualTo("approver"));
        assertThat(store.findConsent("codex", "u-1", "https://stack.example.com/billing"))
                .isEmpty();

        store.deleteConsent("codex", "u-1", "https://stack.example.com/orders");
        assertThat(store.findConsent("codex", "u-1", "https://stack.example.com/orders"))
                .isEmpty();
    }

    @Test
    void expiredRowsPrune() {
        Instant past = Instant.now().minus(Duration.ofDays(1));
        String codeHash = UUID.randomUUID().toString();
        String tokenHash = UUID.randomUUID().toString();
        store.saveCode(new IssuedCode(codeHash, "codex", "u-1", "eve", null, null, null, null,
                past));
        store.saveRefreshToken(new IssuedRefreshToken(tokenHash,
                UUID.randomUUID().toString(), "codex", "u-1", "eve", null, null,
                past.minus(Duration.ofDays(30)), past, null, null));

        store.deleteExpired(Instant.now());

        assertThat(store.consumeCode(codeHash)).isEmpty();
        assertThat(store.findRefreshToken(tokenHash)).isEmpty();
    }

    private static IssuedRefreshToken token(String hash, String chain, Instant now) {
        return new IssuedRefreshToken(hash, chain, "codex", "u-1", "eve",
                "https://stack.example.com/orders", null, now, now.plus(Duration.ofDays(30)),
                null, null);
    }
}
