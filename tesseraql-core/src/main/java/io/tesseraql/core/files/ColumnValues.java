package io.tesseraql.core.files;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.ParsePosition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Typed parsing and locale-aware rendering of file transfer values (design ch. 28). On import a
 * column's declared type turns the file's text into a typed SQL parameter ({@link LocalDate},
 * {@link LocalDateTime}, {@link BigDecimal}) - no dialect-dependent casts, and bad values surface
 * as per-row errors. On text output (CSV) the format renders dates and numbers with the
 * transfer's locale and time zone, so the same route can produce {@code 1,234.56} for one user
 * and {@code 1.234,56} for another.
 */
public final class ColumnValues {

    private static final String DEFAULT_DATE = "yyyy-MM-dd";
    private static final String DEFAULT_DATETIME = "yyyy-MM-dd HH:mm:ss";

    private ColumnValues() {
    }

    /** Resolves a BCP-47 language tag, null meaning the platform default. */
    public static Locale locale(String tag) {
        return tag == null || tag.isBlank() ? Locale.getDefault() : Locale.forLanguageTag(tag);
    }

    /** Resolves a zone id, null meaning the platform default. */
    public static ZoneId zone(String id) {
        return id == null || id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
    }

    /**
     * Parses every typed column of an imported row; untyped values pass through as-is, and
     * values a codec already delivered typed (native workbook date/number cells) are normalized
     * to the declared type without re-parsing.
     */
    public static Map<String, Object> parseRow(FileReadSpec spec, Map<String, Object> values) {
        if (spec.columns().isEmpty()) {
            return values;
        }
        Map<String, Object> typed = new LinkedHashMap<>(values);
        for (ColumnMapping column : spec.columns()) {
            if (column.type() != null && typed.containsKey(column.name())) {
                Object raw = typed.get(column.name());
                typed.put(column.name(), raw instanceof String text
                        ? parse(column, text, locale(spec.locale()))
                        : normalize(column, raw));
            }
        }
        return typed;
    }

    /** Aligns an already-typed value (native workbook cell) with the declared column type. */
    private static Object normalize(ColumnMapping column, Object value) {
        if (value == null) {
            return null;
        }
        if ("date".equals(column.type()) && value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return value;
    }

    /** Parses one value per the column's type; blank input is null. */
    public static Object parse(ColumnMapping column, String raw, Locale locale) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            return switch (column.type()) {
                case "date" -> LocalDate.parse(text, DateTimeFormatter.ofPattern(
                        column.format() == null ? DEFAULT_DATE : column.format(), locale));
                case "datetime" -> LocalDateTime.parse(text, DateTimeFormatter.ofPattern(
                        column.format() == null ? DEFAULT_DATETIME : column.format(), locale));
                case "number" -> parseNumber(column, text, locale);
                default -> throw new IllegalArgumentException(
                        "Unknown column type '" + column.type() + "'");
            };
        } catch (RuntimeException ex) {
            throw new ColumnValueException(column.name(), text, "is not a valid " + column.type()
                    + (column.format() == null ? "" : " (" + column.format() + ")"));
        }
    }

    private static BigDecimal parseNumber(ColumnMapping column, String text, Locale locale) {
        if (column.format() == null) {
            return new BigDecimal(text);
        }
        DecimalFormat format = new DecimalFormat(column.format(),
                DecimalFormatSymbols.getInstance(locale));
        format.setParseBigDecimal(true);
        ParsePosition position = new ParsePosition(0);
        Object parsed = format.parse(text, position);
        if (parsed == null || position.getIndex() != text.length()) {
            throw new IllegalArgumentException("not a number");
        }
        return (BigDecimal) parsed;
    }

    /**
     * Renders one value for text output: formatted per the column's format (with the transfer's
     * locale and time zone) when one applies, the value itself otherwise.
     */
    public static Object format(ColumnMapping column, Object value, Locale locale, ZoneId zone) {
        if (value == null) {
            return null;
        }
        if (column.format() != null && value instanceof Number number) {
            return new DecimalFormat(column.format(),
                    DecimalFormatSymbols.getInstance(locale)).format(number);
        }
        LocalTime time = toLocalTime(value);
        if (time != null) {
            // A time of day has no date and no instant: the zone never applies, a mismatched
            // type: does not invent a date, and a declared format may use only time fields.
            return column.format() != null
                    ? DateTimeFormatter.ofPattern(column.format(), locale).format(time)
                    : DateTimeFormatter.ISO_LOCAL_TIME.format(time);
        }
        ZonedDateTime temporal = toZoned(value, zone);
        if (temporal != null && (column.format() != null || isTemporalType(column))) {
            String pattern = column.format() != null
                    ? column.format()
                    : "date".equals(column.type()) ? DEFAULT_DATE : DEFAULT_DATETIME;
            return DateTimeFormatter.ofPattern(pattern, locale).format(temporal);
        }
        return value;
    }

    private static boolean isTemporalType(ColumnMapping column) {
        return "date".equals(column.type()) || "datetime".equals(column.type());
    }

    /**
     * The wall-clock time of a time-of-day value ({@link java.sql.Time} from five of the six
     * drivers, {@link LocalTime} from DuckDB, {@link OffsetTime} from a {@code time with time
     * zone}), or null for anything else - a null value included. A time has no date and no
     * instant, so it is never zoned; an offset is dropped, never applied.
     */
    public static LocalTime toLocalTime(Object value) {
        return switch (value) {
            case null -> null;
            // The driver built the Time in the JVM zone and toLocalTime() reads it back in that
            // same zone, whatever it is. Never decode getTime() as seconds since midnight UTC:
            // that reads 22:30 as 13:30 on every JVM whose zone is not UTC.
            case java.sql.Time time -> time.toLocalTime();
            case LocalTime time -> time;
            case OffsetTime time -> time.toLocalTime();
            default -> null;
        };
    }

    /**
     * Normalizes the JDBC/temporal types to a zoned date-time, or null for non-temporals - a
     * null value and a time of day included: the Excel writers ask before their own null arm,
     * and a {@link java.sql.Time} is a {@link java.util.Date} whose {@code toInstant()} throws.
     */
    public static ZonedDateTime toZoned(Object value, ZoneId zone) {
        return switch (value) {
            case null -> null;
            case java.sql.Time _ -> null;
            case java.sql.Date date -> date.toLocalDate().atStartOfDay(zone);
            case java.sql.Timestamp timestamp -> timestamp.toInstant().atZone(zone);
            case java.util.Date date -> date.toInstant().atZone(zone);
            case Instant instant -> instant.atZone(zone);
            case LocalDate date -> date.atStartOfDay(zone);
            case LocalDateTime dateTime -> dateTime.atZone(zone);
            case OffsetDateTime dateTime -> dateTime.atZoneSameInstant(zone);
            case ZonedDateTime dateTime -> dateTime.withZoneSameInstant(zone);
            default -> null;
        };
    }
}
