package io.tesseraql.core.dialect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * One text per temporal kind (docs/temporal-semantics.md, decisions 1-5): seconds always, a
 * fraction only when it is not zero and at the value's own precision, so the text of a value
 * does not change with its precision or with the host.
 *
 * <p>Two spellings, one rule. The wire spelling ({@link #wire}) is ISO-8601 with {@code T}, an
 * instant at UTC with {@code Z} — what a JSON response carries. The SQL spelling
 * ({@link #sql}) is the one a person reads in a query tool, with a space, an instant presented
 * in the zone the export declares — what an untyped export cell carries.
 */
public final class TemporalText {

    private static final DateTimeFormatter WIRE_DATE_TIME = withTime(
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral('T'))
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter WIRE_INSTANT = withTime(
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral('T'))
            .appendLiteral('Z').toFormatter(Locale.ROOT);

    private static final DateTimeFormatter SQL_DATE_TIME = withTime(
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral(' '))
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TIME = withTime(new DateTimeFormatterBuilder())
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TIME_WITH_OFFSET = withTime(
            new DateTimeFormatterBuilder()).appendOffsetId().toFormatter(Locale.ROOT);

    private TemporalText() {
    }

    /**
     * The wire text of a temporal, or null when the value is not one: a wall clock without a
     * zone designator, an instant at UTC, a date, a time of day, a time with its offset.
     */
    public static String wire(Object value) {
        return switch (value) {
            case LocalDateTime wallClock -> WIRE_DATE_TIME.format(wallClock);
            case OffsetDateTime instant -> WIRE_INSTANT.format(
                    instant.withOffsetSameInstant(ZoneOffset.UTC));
            case ZonedDateTime instant -> WIRE_INSTANT.format(
                    instant.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC));
            case Instant instant -> WIRE_INSTANT.format(instant.atOffset(ZoneOffset.UTC));
            case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
            case LocalTime time -> TIME.format(time);
            case OffsetTime time -> TIME_WITH_OFFSET.format(time);
            case null, default -> null;
        };
    }

    /**
     * The SQL-style text of a temporal, or null when the value is not one: a wall clock as
     * stored, an instant presented in {@code zone} (the export's own; the platform's when it
     * declares none), a date, a time of day, a time with its offset.
     */
    public static String sql(Object value, ZoneId zone) {
        ZoneId presented = zone == null ? ZoneId.systemDefault() : zone;
        return switch (value) {
            case LocalDateTime wallClock -> SQL_DATE_TIME.format(wallClock);
            case OffsetDateTime instant -> SQL_DATE_TIME.format(
                    instant.atZoneSameInstant(presented));
            case ZonedDateTime instant -> SQL_DATE_TIME.format(
                    instant.withZoneSameInstant(presented));
            case Instant instant -> SQL_DATE_TIME.format(instant.atZone(presented));
            case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
            case LocalTime time -> TIME.format(time);
            case OffsetTime time -> TIME_WITH_OFFSET.format(time);
            case null, default -> null;
        };
    }

    /** {@code HH:mm:ss}, then a fraction only when present, at the value's own precision. */
    private static DateTimeFormatterBuilder withTime(DateTimeFormatterBuilder builder) {
        return builder.appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':')
                .appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral(':')
                .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true);
    }
}
