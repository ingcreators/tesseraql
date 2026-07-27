package io.tesseraql.security.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.telemetry.AggregatingMeter;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The keyed budgets of docs/credential-throttle.md: failures only, independent keys,
 * success clears only the login side, windows expire on their own — never a lockout.
 */
class CredentialThrottleTest {

    private static CredentialThrottle.Config config(int loginAttempts, Duration loginWindow) {
        return new CredentialThrottle.Config(true, loginAttempts, loginWindow, 100,
                Duration.ofMinutes(15));
    }

    @Test
    void theLoginBudgetTripsAtItsLimitAndReportsTheWait() {
        AggregatingMeter meter = new AggregatingMeter();
        CredentialThrottle throttle = new CredentialThrottle(
                config(2, Duration.ofMinutes(15)), meter);

        assertThat(throttle.retryAfter("login", "alice", "203.0.113.7")).isEmpty();
        throttle.recordFailure("alice", "203.0.113.7");
        throttle.recordFailure("alice", "203.0.113.7");

        assertThat(throttle.retryAfter("login", "alice", "203.0.113.7"))
                .hasValueSatisfying(wait -> assertThat(wait).isPositive());
        // The submitted id normalizes: case and whitespace do not mint a fresh budget.
        assertThat(throttle.retryAfter("login", "  ALICE ", "203.0.113.7")).isPresent();
        // Another login id is untouched.
        assertThat(throttle.retryAfter("login", "bob", "198.51.100.2")).isEmpty();
        assertThat(meter.counterSnapshot()).containsKey("tesseraql.credential.throttled");
    }

    @Test
    void successClearsTheLoginBudgetButNeverTheAddressBudget() {
        CredentialThrottle throttle = new CredentialThrottle(
                new CredentialThrottle.Config(true, 2, Duration.ofMinutes(15), 3,
                        Duration.ofMinutes(15)),
                null);
        throttle.recordFailure("alice", "203.0.113.7");
        throttle.recordFailure("alice", "203.0.113.7");
        throttle.recordFailure("bob", "203.0.113.7");

        throttle.recordSuccess("alice");

        // Alice may try again - nothing smolders after the right password.
        assertThat(throttle.retryAfter("login", "alice", "198.51.100.9")).isEmpty();
        // The address that sprayed three accounts is still at its limit.
        assertThat(throttle.retryAfter("login", "carol", "203.0.113.7")).isPresent();
    }

    @Test
    void windowsExpireOnTheirOwnThereIsNoLockout() throws Exception {
        CredentialThrottle throttle = new CredentialThrottle(
                config(1, Duration.ofMillis(60)), null);
        throttle.recordFailure("alice", null);
        assertThat(throttle.retryAfter("login", "alice", null)).isPresent();

        Thread.sleep(120);

        assertThat(throttle.retryAfter("login", "alice", null)).isEmpty();
    }

    @Test
    void disabledIsInertAndNullKeysNeverThrottle() {
        CredentialThrottle disabled = new CredentialThrottle(
                new CredentialThrottle.Config(false, 1, Duration.ofMinutes(1), 1,
                        Duration.ofMinutes(1)),
                null);
        disabled.recordFailure("alice", "203.0.113.7");
        disabled.recordFailure("alice", "203.0.113.7");
        assertThat(disabled.retryAfter("login", "alice", "203.0.113.7")).isEmpty();

        CredentialThrottle enabled = new CredentialThrottle(
                config(1, Duration.ofMinutes(15)), null);
        enabled.recordFailure(null, null);
        assertThat(enabled.retryAfter("confirm", null, null)).isEmpty();
    }
}
