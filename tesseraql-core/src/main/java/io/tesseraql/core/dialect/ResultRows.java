package io.tesseraql.core.dialect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;

/**
 * How a JDBC row becomes a bindable map: the column label a response binding writes, and the
 * value it reads.
 *
 * <p>There were two answers. The SQL producer normalized labels per dialect and converted JDBC
 * temporals to ISO-8601 strings; a command's query-steps force-lowercased every label and passed
 * {@code java.sql.Timestamp} straight through. So the same {@code select … as "orderTotal"} came
 * back as {@code orderTotal} on a query route and {@code ordertotal} inside a command on Oracle,
 * and a date rendered as {@code 2026-07-25} on one path and as whatever {@code toString()} a
 * driver's temporal happens to have on the other. A response binding written against one path
 * broke on the other, and nothing said why.
 *
 * <p>The policy, stated as a rule rather than as a count of who follows it: a reader that hands
 * rows straight to a response binding asks here for BOTH halves. The route reader, the command
 * readers, the contract reader behind {@code SqlStatement.query} and the workflow transition
 * reader all do, so one store answers the same column the same way whichever path read it.
 *
 * <p>Two deliberate departures, each with the same reason — the rows are consumed by a later step
 * that binds them, and an ISO-8601 string is not a timestamp. The batch executor's step, keyset
 * and chunk readers keep values typed (docs/sql-execution-shapes.md structural decision 1), and so
 * does the enrichment reader, whose columns are composed into rows a writer binds. Both ask here
 * for the label and answer the value themselves.
 *
 * <p>Said plainly because a sweeping claim stood here and was false: this class does not know its
 * callers and cannot enforce the rule. Readers outside the framework's own row paths — the
 * reference lookup's, the declarative suite's and Studio's — do not ask here at all.
 */
public final class ResultRows {

    private ResultRows() {
    }

    /**
     * The bindable form of a column label: dialect-normalized, so a quoted mixed-case alias
     * survives on Oracle while an unquoted identifier the driver upper-cased comes back lower.
     */
    public static String label(String dialect, String columnLabel) {
        return Labels.normalize(dialect, columnLabel);
    }

    /**
     * The bindable form of a column value: one canonical text per temporal kind, the JSON-native
     * kinds as they are, and anything else as its text (docs/temporal-semantics.md, decisions
     * 1-4 and 7).
     *
     * <p>A wall clock prints without a zone designator ({@code 2026-01-15T22:30:00.123456}) —
     * it never had one, and printing it as a UTC instant is what made the same row read
     * {@code T13:30:00Z} on a Tokyo host. An instant prints at UTC with {@code Z}, a time with
     * zone with its offset. Seconds always print and a fraction only when it is not zero, so the
     * text of a value does not change with the value's precision. A {@code java.sql} temporal
     * still arriving here is converted first ({@link JdbcValues#normalize}). Everything the
     * framework does not know — a driver's JSON or interval wrapper, an array — is its
     * {@code toString()}: a JSON mapper given the object itself either threw ({@code java.time})
     * or wrote the object's bean shape ({@code {"type":"jsonb","value":…}}).
     */
    public static Object value(Object value) {
        return switch (value) {
            case null -> null;
            case String text -> text;
            case Boolean flag -> flag;
            case Number number -> number;
            case byte[] bytes -> bytes;
            case java.util.UUID uuid -> uuid;
            case LocalDateTime wallClock -> WALL_CLOCK.format(wallClock);
            case OffsetDateTime instant -> INSTANT.format(
                    instant.withOffsetSameInstant(ZoneOffset.UTC));
            case ZonedDateTime instant -> INSTANT.format(
                    instant.toOffsetDateTime().withOffsetSameInstant(ZoneOffset.UTC));
            case Instant instant -> INSTANT.format(instant.atOffset(ZoneOffset.UTC));
            case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
            case LocalTime time -> TIME.format(time);
            case OffsetTime time -> TIME_WITH_OFFSET.format(time);
            case java.util.Date legacy -> value(JdbcValues.normalize(legacy));
            default -> String.valueOf(value);
        };
    }

    /** Seconds always, a fraction only when present, at the value's own precision. */
    private static DateTimeFormatterBuilder secondsAndFraction(DateTimeFormatterBuilder builder) {
        return builder.appendValue(ChronoField.HOUR_OF_DAY, 2).appendLiteral(':')
                .appendValue(ChronoField.MINUTE_OF_HOUR, 2).appendLiteral(':')
                .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
                .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true);
    }

    private static final DateTimeFormatter WALL_CLOCK = secondsAndFraction(
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral('T'))
            .toFormatter(Locale.ROOT);

    private static final DateTimeFormatter INSTANT = secondsAndFraction(
            new DateTimeFormatterBuilder().append(DateTimeFormatter.ISO_LOCAL_DATE)
                    .appendLiteral('T'))
            .appendLiteral('Z').toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TIME = secondsAndFraction(
            new DateTimeFormatterBuilder()).toFormatter(Locale.ROOT);

    private static final DateTimeFormatter TIME_WITH_OFFSET = secondsAndFraction(
            new DateTimeFormatterBuilder()).appendOffsetId().toFormatter(Locale.ROOT);
}
