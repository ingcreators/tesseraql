package io.tesseraql.core.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;

class ColumnValuesTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void typedParsingProducesSqlReadyValues() {
        ColumnMapping date = new ColumnMapping("heldOn", null, null, "date", "yyyy/MM/dd");
        assertThat(ColumnValues.parse(date, "2026/06/11", Locale.JAPAN))
                .isEqualTo(LocalDate.of(2026, 6, 11));

        ColumnMapping plain = new ColumnMapping("qty", null, null, "number", null);
        assertThat(ColumnValues.parse(plain, "12.5", Locale.US))
                .isEqualTo(new BigDecimal("12.5"));

        assertThat(ColumnValues.parse(date, "  ", Locale.US)).isNull();
    }

    @Test
    void localizedNumberFormatsParsePerLocale() {
        ColumnMapping fee = new ColumnMapping("fee", null, null, "number", "#,##0.00");
        assertThat(ColumnValues.parse(fee, "1.234,56", Locale.GERMANY))
                .isEqualTo(new BigDecimal("1234.56"));
        assertThat(ColumnValues.parse(fee, "1,234.56", Locale.US))
                .isEqualTo(new BigDecimal("1234.56"));
    }

    @Test
    void badValuesFailWithTheColumnAndPattern() {
        ColumnMapping date = new ColumnMapping("heldOn", null, null, "date", "yyyy/MM/dd");
        assertThatThrownBy(() -> ColumnValues.parse(date, "11-06-2026", Locale.US))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("heldOn")
                .hasMessageContaining("yyyy/MM/dd");
    }

    @Test
    void formattingRendersWithLocaleAndTimeZone() {
        ColumnMapping stamp = new ColumnMapping("createdAt", null, null, null, "yyyy/MM/dd HH:mm");
        Timestamp utcMidnight = Timestamp.from(java.time.Instant.parse("2026-06-10T23:30:00Z"));
        assertThat(ColumnValues.format(stamp, utcMidnight,
                Locale.JAPAN, ZoneId.of("Asia/Tokyo")))
                .isEqualTo("2026/06/11 08:30");

        ColumnMapping fee = new ColumnMapping("fee", null, null, "number", "#,##0.00");
        assertThat(ColumnValues.format(fee, new BigDecimal("1234.5"),
                Locale.GERMANY, ZoneId.systemDefault()))
                .isEqualTo("1.234,50");

        // No format and no temporal type: the value passes through untouched.
        ColumnMapping plain = ColumnMapping.of("name");
        assertThat(ColumnValues.format(plain, "alpha", Locale.US, ZoneId.systemDefault()))
                .isEqualTo("alpha");
    }

    @Test
    void aNullValueIsNotATemporal() {
        // The Excel writers ask the two normalizers before their own null arm: a NULL cell must
        // come back as "not mine" from both, never as an NPE (the old pattern switch had no
        // case null).
        assertThat(ColumnValues.toZoned(null, TOKYO)).isNull();
        assertThat(ColumnValues.toLocalTime(null)).isNull();
        // format() had its own null arm all along - the control that a NULL is a NULL on CSV.
        assertThat(ColumnValues.format(ColumnMapping.of("note"), null, Locale.US, TOKYO)).isNull();
    }

    @Test
    void aSqlTimeRendersAsWallClockText() {
        java.sql.Time time = java.sql.Time.valueOf("22:30:00");
        // Untyped: ISO wall-clock text, seconds always present.
        assertThat(ColumnValues.format(ColumnMapping.of("starts_at"), time, Locale.US, TOKYO))
                .isEqualTo("22:30:00");
        // A column format formats the LocalTime.
        ColumnMapping formatted = new ColumnMapping("starts_at", null, null, null, "HH:mm");
        assertThat(ColumnValues.format(formatted, time, Locale.US, TOKYO)).isEqualTo("22:30");
        // A mismatched type: does not invent a date around a time of day - neither datetime nor
        // date - and a format declared beside the wrong type still applies to the time.
        ColumnMapping asDatetime = new ColumnMapping("starts_at", null, null, "datetime", null);
        assertThat(ColumnValues.format(asDatetime, time, Locale.US, TOKYO)).isEqualTo("22:30:00");
        ColumnMapping asDate = new ColumnMapping("starts_at", null, null, "date", null);
        assertThat(ColumnValues.format(asDate, time, Locale.US, TOKYO)).isEqualTo("22:30:00");
        ColumnMapping asDateFormatted = new ColumnMapping("starts_at", null, null, "date", "HH:mm");
        assertThat(ColumnValues.format(asDateFormatted, time, Locale.US, TOKYO)).isEqualTo("22:30");
        // A java.sql.Time is a java.util.Date whose toInstant() throws: toZoned must not ask.
        assertThatCode(() -> ColumnValues.toZoned(time, TOKYO)).doesNotThrowAnyException();
        assertThat(ColumnValues.toZoned(time, TOKYO)).as("a time of day has no instant").isNull();
    }

    @Test
    void aLocalOrOffsetTimeRendersLikeASqlTime() {
        // Decision 12: DuckDB's LocalTime and H2/DuckDB's OffsetTime render as wall-clock text
        // like the java.sql.Time arm. An offset is dropped, never applied - and the fixture's
        // offset is NOT the export zone's, so a codec that shifts the time into the export zone
        // (12:30 / 13:30) is told from one that keeps the wall clock.
        assertThat(ColumnValues.format(ColumnMapping.of("t"), LocalTime.of(22, 30), Locale.US,
                TOKYO)).isEqualTo("22:30:00");
        assertThat(ColumnValues.format(ColumnMapping.of("t"),
                OffsetTime.of(22, 30, 0, 0, ZoneOffset.ofHours(-5)), Locale.US, TOKYO))
                .as("22:30-05:00 under Asia/Tokyo keeps its wall clock").isEqualTo("22:30:00");
        assertThat(ColumnValues.format(ColumnMapping.of("t"),
                OffsetTime.of(22, 30, 0, 0, ZoneOffset.ofHours(9)), Locale.US, UTC))
                .as("22:30+09:00 under UTC keeps its wall clock").isEqualTo("22:30:00");
        ColumnMapping formatted = new ColumnMapping("t", null, null, null, "HH:mm");
        assertThat(ColumnValues.format(formatted, LocalTime.of(22, 30, 5), Locale.US, TOKYO))
                .isEqualTo("22:30");
        // A LocalTime keeps its fraction of a second, and ISO prints it only when present.
        assertThat(ColumnValues.format(ColumnMapping.of("t"),
                LocalTime.of(22, 30, 0, 500_000_000), Locale.US, UTC)).isEqualTo("22:30:00.5");
        assertThat(ColumnValues.format(ColumnMapping.of("t"),
                OffsetTime.of(22, 30, 0, 500_000_000, ZoneOffset.ofHours(9)), Locale.US, UTC))
                .isEqualTo("22:30:00.5");
    }

    /**
     * A java.sql.Time is built by the driver in the JVM zone and carries no instant of its own:
     * the wall clock must survive whatever the JVM zone is and whatever the export zone says.
     * Red on a Time decoded as UTC seconds-of-day (13:30 on a JST JVM) and on a Time moved into
     * the export zone as an instant (13:30 under UTC); the host's own zone cannot make either
     * green because the test sets the JVM zone itself. JUnit runs sequentially in this module,
     * and the default is restored in finally.
     */
    @Test
    void aSqlTimeKeepsItsWallClockUnderEveryJvmAndExportZone() {
        TimeZone before = TimeZone.getDefault();
        try {
            for (String jvmZone : new String[]{"Asia/Tokyo", "America/New_York", "UTC"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(jvmZone));
                java.sql.Time time = java.sql.Time.valueOf("22:30:00"); // as pgjdbc builds it
                for (String exportZone : new String[]{"UTC", "Asia/Tokyo", "Pacific/Auckland"}) {
                    assertThat(ColumnValues.format(ColumnMapping.of("t"), time, Locale.US,
                            ZoneId.of(exportZone)))
                            .as("jvm %s export %s", jvmZone, exportZone).isEqualTo("22:30:00");
                }
                assertThat(ColumnValues.toLocalTime(time))
                        .as("jvm %s", jvmZone).isEqualTo(LocalTime.of(22, 30));
            }
        } finally {
            TimeZone.setDefault(before);
        }
    }

    /**
     * The pattern is applied in the export's locale: a locale-sensitive field under Locale.JAPAN
     * and under Locale.US, so a time arm that drops the locale is red on any JVM locale.
     */
    @Test
    void aColumnFormatOnATimeUsesTheExportLocale() {
        ColumnMapping formatted = new ColumnMapping("t", null, null, null, "hh:mm a");
        java.sql.Time time = java.sql.Time.valueOf("22:30:00");
        assertThat(ColumnValues.format(formatted, time, Locale.JAPAN, UTC)).isEqualTo("10:30 午後");
        assertThat(ColumnValues.format(formatted, time, Locale.US, UTC)).isEqualTo("10:30 PM");
    }

    /**
     * A java.sql.Time carrying milliseconds (pgjdbc hands one for a time(3)) renders without
     * them - the JSON path's precedent, and a text that does not depend on the JVM zone through
     * getTime(). Built with new Time(millis): Time.valueOf cannot carry a fraction.
     */
    @Test
    void aSqlTimeWithMillisecondsRendersWithoutThem() {
        java.sql.Time withMillis = new java.sql.Time(
                java.sql.Time.valueOf("22:30:00").getTime() + 500);
        assertThat(ColumnValues.format(ColumnMapping.of("t"), withMillis, Locale.US, UTC))
                .isEqualTo("22:30:00");
    }
}
