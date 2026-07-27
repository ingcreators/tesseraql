package io.tesseraql.security.throttle;

import io.tesseraql.core.telemetry.Meter;
import io.tesseraql.core.telemetry.NoopMeter;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Keyed, failures-only, fixed-window throttle over the credential surfaces
 * (docs/credential-throttle.md). Two independent budgets: the <em>submitted</em> login id
 * (primary — applied before any existence check, so a nonexistent account throttles
 * identically and the throttle is never an enumeration oracle) and the presented address
 * (secondary — XFF-first, spoof-rotation accepted; the login key is the one that must
 * hold). Never a lockout: windows expire on their own.
 *
 * <p>Node-local and bounded (the session-store idiom: expired windows pruned on write,
 * oldest evicted at the cap). Behind a round-robin balancer the budget multiplies by the
 * node count — accepted for failures-only counting at the shipped defaults; a
 * shared-store keyed window is the recorded follow-up.
 */
public final class CredentialThrottle {

    /** Budgets and windows; {@code enabled: false} makes every call a no-op. */
    public record Config(boolean enabled, int loginAttempts, Duration loginWindow,
            int addressAttempts, Duration addressWindow) {

        public static Config defaults() {
            return new Config(true, 10, Duration.ofMinutes(15), 100, Duration.ofMinutes(15));
        }
    }

    private static final int MAX_KEYS = 50_000;

    private record Window(Instant start, int failures) {
    }

    private final Config config;
    private final Meter meter;
    private final ConcurrentMap<String, Window> byLogin = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Window> byAddress = new ConcurrentHashMap<>();

    public CredentialThrottle(Config config, Meter meter) {
        this.config = config == null ? Config.defaults() : config;
        this.meter = meter == null ? NoopMeter.INSTANCE : meter;
    }

    /**
     * The wait before this caller may try again, or empty when it may proceed now. A hit
     * counts into {@code tesseraql.credential.throttled} with the surface and the key kind
     * that tripped. Checked <em>before</em> credential verification, so a throttled
     * request never pays the hashing cost.
     */
    public Optional<Duration> retryAfter(String surface, String loginId, String address) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Optional<Duration> wait = remaining(byLogin, loginKey(loginId),
                config.loginAttempts(), config.loginWindow(), now);
        String key = "login";
        if (wait.isEmpty()) {
            wait = remaining(byAddress, addressKey(address),
                    config.addressAttempts(), config.addressWindow(), now);
            key = "address";
        }
        if (wait.isPresent()) {
            meter.counter("tesseraql.credential.throttled")
                    .increment(Map.of("surface", surface, "key", key));
        }
        return wait;
    }

    /** Counts one failed attempt against both keys. */
    public void recordFailure(String loginId, String address) {
        if (!config.enabled()) {
            return;
        }
        Instant now = Instant.now();
        bump(byLogin, loginKey(loginId), config.loginWindow(), now);
        bump(byAddress, addressKey(address), config.addressWindow(), now);
    }

    /**
     * A successful verification clears the login budget — nine mistakes followed by the
     * right password leave nothing smoldering. The address budget deliberately stands:
     * one success from an address spraying many accounts clears nothing.
     */
    public void recordSuccess(String loginId) {
        if (config.enabled()) {
            String key = loginKey(loginId);
            if (key != null) {
                byLogin.remove(key);
            }
        }
    }

    private static String loginKey(String loginId) {
        return loginId == null || loginId.isBlank()
                ? null
                : loginId.trim().toLowerCase(Locale.ROOT);
    }

    private static String addressKey(String address) {
        return address == null || address.isBlank() ? null : address.trim();
    }

    private Optional<Duration> remaining(ConcurrentMap<String, Window> windows, String key,
            int attempts, Duration windowLength, Instant now) {
        if (key == null) {
            return Optional.empty();
        }
        Window window = windows.get(key);
        if (window == null || window.start().plus(windowLength).isBefore(now)) {
            return Optional.empty();
        }
        if (window.failures() < attempts) {
            return Optional.empty();
        }
        Duration wait = Duration.between(now, window.start().plus(windowLength));
        return Optional.of(wait.isNegative() ? Duration.ZERO : wait);
    }

    private void bump(ConcurrentMap<String, Window> windows, String key,
            Duration windowLength, Instant now) {
        if (key == null) {
            return;
        }
        prune(windows, windowLength, now);
        windows.merge(key, new Window(now, 1),
                (current, fresh) -> current.start().plus(windowLength).isBefore(now)
                        ? fresh
                        : new Window(current.start(), current.failures() + 1));
    }

    /** Expired windows leave on write; the cap evicts the oldest — the 50k idiom. */
    private void prune(ConcurrentMap<String, Window> windows, Duration windowLength,
            Instant now) {
        windows.entrySet()
                .removeIf(entry -> entry.getValue().start().plus(windowLength).isBefore(now));
        if (windows.size() < MAX_KEYS) {
            return;
        }
        windows.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().start()))
                .limit(Math.max(1, windows.size() - MAX_KEYS + 1))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(windows::remove);
    }
}
