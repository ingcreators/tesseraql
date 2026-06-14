package io.tesseraql.security.jwt;

import io.tesseraql.core.error.TqlException;
import java.net.URI;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * A {@link KeySource} backed by a JWKS endpoint, caching the {@code kid}-to-key map and refreshing
 * on rotation (design ch. 11.1). Two timers bound the network: the cache is refreshed once it is
 * older than {@code cacheTtl}, and an unknown {@code kid} (a key rotated in since the last fetch)
 * triggers at most one refetch per {@code refreshFloor} — so a flood of tokens carrying random
 * {@code kid}s cannot turn into a flood of JWKS requests. An unknown {@code kid} that survives a
 * permitted refetch fails closed.
 */
public final class JwksKeySource implements KeySource {

    private final JwksFetcher fetcher;
    private final URI uri;
    private final long cacheTtlMillis;
    private final long refreshFloorMillis;
    private final LongSupplier clock;
    private final Object lock = new Object();

    private volatile Map<String, RSAPublicKey> keys = Map.of();
    private volatile long lastFetchAt = Long.MIN_VALUE;

    public JwksKeySource(JwksFetcher fetcher, URI uri, Duration cacheTtl, Duration refreshFloor) {
        this(fetcher, uri, cacheTtl, refreshFloor, System::currentTimeMillis);
    }

    JwksKeySource(JwksFetcher fetcher, URI uri, Duration cacheTtl, Duration refreshFloor,
            LongSupplier clock) {
        this.fetcher = fetcher;
        this.uri = uri;
        this.cacheTtlMillis = cacheTtl.toMillis();
        this.refreshFloorMillis = refreshFloor.toMillis();
        this.clock = clock;
    }

    @Override
    public RSAPublicKey resolve(String kid) {
        String wanted = kid == null ? "" : kid;
        ensureLoaded();
        RSAPublicKey key = lookup(keys, wanted);
        if (key != null) {
            return key;
        }
        // Unknown kid: the IdP may have rotated keys. Refetch — but only past the floor, so random
        // kids cannot drive unbounded fetches.
        if (clock.getAsLong() - lastFetchAt >= refreshFloorMillis) {
            refetch();
            key = lookup(keys, wanted);
            if (key != null) {
                return key;
            }
        }
        throw new TqlException(SignatureVerifier.UNAUTHORIZED, "Unknown JWT kid: " + kid);
    }

    private void ensureLoaded() {
        if (keys.isEmpty() || clock.getAsLong() - lastFetchAt >= cacheTtlMillis) {
            refetch();
        }
    }

    private void refetch() {
        synchronized (lock) {
            long now = clock.getAsLong();
            try {
                keys = fetcher.fetch(uri);
            } catch (RuntimeException ex) {
                // Serve the last good key set on a transient JWKS failure; fail closed if we never
                // fetched one. The attempt time still advances so the floor rate-limits retries.
                if (keys.isEmpty()) {
                    lastFetchAt = now;
                    throw ex;
                }
            }
            lastFetchAt = now;
        }
    }

    private static RSAPublicKey lookup(Map<String, RSAPublicKey> current, String kid) {
        if (!kid.isEmpty()) {
            return current.get(kid);
        }
        if (current.size() == 1) {
            return current.values().iterator().next();
        }
        return current.get("");
    }
}
