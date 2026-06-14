package io.tesseraql.security.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.net.URI;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/** The JWKS cache: fetch-once, rotate on unknown kid, and rate-limit unknown-kid refetches. */
class JwksKeySourceTest {

    private static final URI URI_JWKS = URI.create("https://idp.example.com/jwks");
    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration FLOOR = Duration.ofMinutes(1);

    /** A fetcher whose key set and call count tests control directly — no network. */
    private static final class FakeFetcher implements JwksFetcher {
        private Map<String, RSAPublicKey> keys;
        private int calls;

        FakeFetcher(Map<String, RSAPublicKey> keys) {
            this.keys = keys;
        }

        @Override
        public Map<String, RSAPublicKey> fetch(URI jwksUri) {
            calls++;
            return keys;
        }
    }

    private static RSAPublicKey rsaKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return (RSAPublicKey) gen.generateKeyPair().getPublic();
    }

    private static JwksKeySource source(FakeFetcher fetcher, LongSupplier clock) {
        return new JwksKeySource(fetcher, URI_JWKS, TTL, FLOOR, clock);
    }

    @Test
    void fetchesOnceWhileCacheIsFresh() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of("kid-a", rsaKey()));
        long[] now = {0};
        JwksKeySource source = source(fetcher, () -> now[0]);

        assertThat(source.resolve("kid-a")).isNotNull();
        assertThat(source.resolve("kid-a")).isNotNull();
        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void refetchesOnceForUnknownKidThenResolvesRotatedKey() throws Exception {
        RSAPublicKey keyA = rsaKey();
        RSAPublicKey keyB = rsaKey();
        FakeFetcher fetcher = new FakeFetcher(new LinkedHashMap<>(Map.of("kid-a", keyA)));
        long[] now = {0};
        JwksKeySource source = source(fetcher, () -> now[0]);

        assertThat(source.resolve("kid-a")).isEqualTo(keyA);

        // The IdP rotates: kid-b appears. A token with the new kid arrives past the refresh floor.
        fetcher.keys = new LinkedHashMap<>(Map.of("kid-a", keyA, "kid-b", keyB));
        now[0] = Duration.ofMinutes(2).toMillis();

        assertThat(source.resolve("kid-b")).isEqualTo(keyB);
        assertThat(fetcher.calls).isEqualTo(2);
    }

    @Test
    void rateLimitsUnknownKidRefetchWithinFloor() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of("kid-a", rsaKey()));
        long[] now = {0};
        JwksKeySource source = source(fetcher, () -> now[0]);

        source.resolve("kid-a");
        // A flood of random kids right after the fetch must not drive more JWKS requests.
        assertThatThrownBy(() -> source.resolve("kid-x")).isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> source.resolve("kid-y")).isInstanceOf(TqlException.class);
        assertThat(fetcher.calls).isEqualTo(1);
    }

    @Test
    void failsClosedForUnknownKidAfterPermittedRefetch() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of("kid-a", rsaKey()));
        long[] now = {0};
        JwksKeySource source = source(fetcher, () -> now[0]);

        source.resolve("kid-a");
        now[0] = Duration.ofMinutes(2).toMillis();
        assertThatThrownBy(() -> source.resolve("kid-x"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("kid");
        assertThat(fetcher.calls).isEqualTo(2);
    }

    @Test
    void usesSoleKeyWhenTokenHasNoKid() throws Exception {
        RSAPublicKey only = rsaKey();
        FakeFetcher fetcher = new FakeFetcher(Map.of("the-only-key", only));
        long[] now = {0};
        JwksKeySource source = source(fetcher, () -> now[0]);

        assertThat(source.resolve(null)).isEqualTo(only);
        assertThat(fetcher.calls).isEqualTo(1);
    }
}
