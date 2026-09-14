package io.tesseraql.core.dialect;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The bindable form of a column value: one canonical text per temporal kind
 * (docs/temporal-semantics.md, decisions 1-4), the JSON-native kinds untouched, anything else its
 * text (decision 7).
 */
class ResultRowsTest {

    @Test
    void aWallClockPrintsWithoutAZoneAndWithItsSeconds() {
        assertThat(ResultRows.value(LocalDateTime.parse("2026-01-15T22:30:00.123456")))
                .isEqualTo("2026-01-15T22:30:00.123456");
        // LocalDateTime.toString drops zero seconds; the wire does not.
        assertThat(ResultRows.value(LocalDateTime.parse("2026-03-08T02:30")))
                .isEqualTo("2026-03-08T02:30:00");
        assertThat(ResultRows.value(LocalDateTime.parse("2026-03-08T02:30:00.5")))
                .isEqualTo("2026-03-08T02:30:00.5");
    }

    @Test
    void anInstantPrintsAtUtcWhateverOffsetItArrivedWith() {
        assertThat(ResultRows.value(OffsetDateTime.parse("2026-01-16T07:30:00.123456+09:00")))
                .isEqualTo("2026-01-15T22:30:00.123456Z");
        assertThat(ResultRows.value(OffsetDateTime.parse("2026-01-15T22:30Z")))
                .isEqualTo("2026-01-15T22:30:00Z");
        assertThat(ResultRows.value(Instant.parse("2026-01-15T22:30:00Z")))
                .isEqualTo("2026-01-15T22:30:00Z");
        assertThat(ResultRows.value(ZonedDateTime.of(LocalDateTime.parse("2026-01-16T07:30"),
                ZoneId.of("Asia/Tokyo")))).isEqualTo("2026-01-15T22:30:00Z");
    }

    @Test
    void aDateATimeAndATimeWithZonePrintTheirOwnKind() {
        assertThat(ResultRows.value(LocalDate.parse("2026-01-15"))).isEqualTo("2026-01-15");
        assertThat(ResultRows.value(LocalTime.parse("22:30"))).isEqualTo("22:30:00");
        assertThat(ResultRows.value(LocalTime.parse("22:30:00.5"))).isEqualTo("22:30:00.5");
        assertThat(ResultRows.value(OffsetTime.parse("22:30+09:00"))).isEqualTo("22:30:00+09:00");
        assertThat(ResultRows.value(OffsetTime.parse("02:30Z"))).isEqualTo("02:30:00Z");
    }

    /** A legacy temporal that still arrives here is its {@code java.time} kind first. */
    @Test
    void aLegacyTemporalIsConvertedBeforeItPrints() {
        assertThat(ResultRows.value(java.sql.Timestamp.valueOf("2026-01-15 22:30:00")))
                .isEqualTo("2026-01-15T22:30:00");
        assertThat(ResultRows.value(java.sql.Date.valueOf("2026-01-15")))
                .isEqualTo("2026-01-15");
        assertThat(ResultRows.value(java.sql.Time.valueOf("22:30:00"))).isEqualTo("22:30:00");
    }

    @Test
    void theJsonNativeKindsPassThroughUntouched() {
        UUID uuid = UUID.randomUUID();
        byte[] bytes = {1, 2};
        assertThat(ResultRows.value("text")).isEqualTo("text");
        assertThat(ResultRows.value(Boolean.TRUE)).isEqualTo(Boolean.TRUE);
        assertThat(ResultRows.value(42)).isEqualTo(42);
        assertThat(ResultRows.value(new BigDecimal("1.50"))).isEqualTo(new BigDecimal("1.50"));
        assertThat(ResultRows.value(uuid)).isSameAs(uuid);
        assertThat(ResultRows.value(bytes)).isSameAs(bytes);
        assertThat(ResultRows.value(null)).isNull();
    }

    /**
     * A driver's own wrapper — a {@code PGobject} for {@code jsonb}, a {@code PGInterval} — is
     * its text: given the object, the JSON mapper wrote its bean shape
     * ({@code {"type":"jsonb","value":…,"null":false}}).
     */
    @Test
    void anythingElseIsItsText() {
        Object wrapper = new Object() {
            @SuppressWarnings("unused")
            public String getType() {
                return "jsonb";
            }

            @Override
            public String toString() {
                return "{\"sku\": \"A-1\"}";
            }
        };
        assertThat(ResultRows.value(wrapper)).isEqualTo("{\"sku\": \"A-1\"}");
    }
}
