package io.tesseraql.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock the tests move forward by hand. It starts at the wall clock rather than a fixed
 * instant because CXF's handlers check code expiry against {@code System.currentTimeMillis()} —
 * a provider clock in the past would fail their check for perfectly valid grants.
 */
final class MutableClock extends Clock {

    private Instant instant = Instant.now();

    void advance(Duration duration) {
        instant = instant.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return instant;
    }
}
