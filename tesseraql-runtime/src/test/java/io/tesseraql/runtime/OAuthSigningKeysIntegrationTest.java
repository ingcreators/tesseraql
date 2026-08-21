package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.oauth.JwksDocuments;
import io.tesseraql.oauth.Rs256TokenSigner;
import io.tesseraql.oauth.SigningKeys;
import io.tesseraql.security.jwt.Jwks;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The signing-key lifecycle on a real database (docs/token-issuance.md slice 3): exactly-once
 * generation under concurrency, overlapping rotation, and an RS256 mint that validates through
 * the same JWKS parser a stack member's bearer validation uses.
 */
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
class OAuthSigningKeysIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static DataSource dataSource;
    static SigningKeys keys;

    @BeforeAll
    static void migrate() {
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(POSTGRES.getJdbcUrl());
        pg.setUser(POSTGRES.getUsername());
        pg.setPassword(POSTGRES.getPassword());
        FrameworkMigrations.migrateSecurity(pg);
        dataSource = pg;
        keys = new SigningKeys(dataSource, Clock.systemUTC());
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void concurrentFirstStartsProduceExactlyOneKey() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = pool.submit(() -> ensureAfter(start));
            Future<String> second = pool.submit(() -> ensureAfter(start));
            start.countDown();
            assertThat(first.get()).isEqualTo(SigningKeys.INITIAL_KID)
                    .isEqualTo(second.get());
        } finally {
            pool.shutdownNow();
        }
        assertThat(keys.published(Duration.ofMinutes(15))).hasSize(1);
    }

    private static String ensureAfter(CountDownLatch start) throws InterruptedException {
        start.await();
        // Each racer has its own store instance, as two replicas would.
        return new SigningKeys(dataSource, Clock.systemUTC()).ensureActive().kid();
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void aMintedTokenValidatesThroughTheMemberSideParser() throws Exception {
        keys.ensureActive();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "u-1");
        claims.put("aud", "https://stack.example.com/orders");

        String token = new Rs256TokenSigner(keys).sign(claims);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        JsonNode header = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[0]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        String kid = header.get("kid").asText();

        // The published document, parsed by the exact class member validation uses.
        Map<String, RSAPublicKey> published = Jwks.parseJwkSet(
                JwksDocuments.render(keys.published(Duration.ofMinutes(15)))
                        .getBytes(StandardCharsets.UTF_8));
        assertThat(published).containsKey(kid);

        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(published.get(kid));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();

        JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload.get("sub").asText()).isEqualTo("u-1");
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void rotationOverlapsAndTheOldKeyEventuallyLeavesTheDocument() {
        keys.ensureActive();
        String before = keys.active().orElseThrow().kid();

        SigningKeys.SigningKey fresh = keys.rotate();

        assertThat(keys.active().orElseThrow().kid()).isEqualTo(fresh.kid());
        // The retired key stays published while tokens it signed can still be alive...
        assertThat(keys.published(Duration.ofMinutes(15)))
                .extracting(SigningKeys.SigningKey::kid).contains(before, fresh.kid());
        // ...and leaves the document once the access-token lifetime has passed since retirement.
        SigningKeys later = new SigningKeys(dataSource,
                Clock.offset(Clock.systemUTC(), Duration.ofMinutes(16)));
        assertThat(later.published(Duration.ofMinutes(15)))
                .extracting(SigningKeys.SigningKey::kid)
                .contains(fresh.kid()).doesNotContain(before);
    }
}
