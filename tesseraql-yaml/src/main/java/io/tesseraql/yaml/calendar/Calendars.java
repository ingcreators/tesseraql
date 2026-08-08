package io.tesseraql.yaml.calendar;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.CalendarsDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The app's business-day calendars (docs/batch-platform.md track B): named weekend/holiday
 * definitions declared once under {@code calendars/}, referenced from a job schedule's
 * {@code calendar:} qualifier. Under the daily-consider model the cron says when to
 * <em>consider</em> a firing and the calendar says whether it <em>counts</em> — a filtered-out
 * firing is skipped silently, it is not a run.
 *
 * <p>Holidays live in exactly one home: a fixed {@code dates:} list for small closed sets, or
 * a table-backed {@code source:} read on the job's datasource at fire time, so operations
 * maintains next year's holidays as rows without a deploy.
 */
public final class Calendars {

    /** TQL-BATCH-4204: the same calendar name is declared in two documents. */
    private static final TqlErrorCode DUPLICATE = new TqlErrorCode(TqlDomain.BATCH, 4204);
    /** TQL-BATCH-4205: a calendar declaration that cannot mean anything at fire time. */
    private static final TqlErrorCode INVALID = new TqlErrorCode(TqlDomain.BATCH, 4205);

    /** The {@code runOn:} qualifiers a schedule may declare (default: {@code business-day}). */
    public static final Set<String> RUN_ON = Set.of("business-day", "first-business-day-of-month",
            "last-business-day-of-month");

    /** The {@code shift:} directions a nominal day may declare (default: next). */
    public static final Set<String> SHIFTS = Set.of("next-business-day", "previous-business-day");

    private static final Set<DayOfWeek> DEFAULT_WEEKEND = Set.of(DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY);
    /** Table and column names reach SQL text verbatim, so they stay plain identifiers. */
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern
            .compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final Map<String, CalendarsDocument.Calendar> calendars;
    private final Map<String, String> sources;

    private Calendars(Map<String, CalendarsDocument.Calendar> calendars,
            Map<String, String> sources) {
        this.calendars = java.util.Collections.unmodifiableMap(calendars);
        this.sources = java.util.Collections.unmodifiableMap(sources);
    }

    /** No calendars — the shape lint falls back to when the directory itself fails to load. */
    public static Calendars empty() {
        return new Calendars(new LinkedHashMap<>(), new LinkedHashMap<>());
    }

    /**
     * Loads every {@code calendars/*.yml} under the app home; duplicate names, unknown day
     * names, unparseable dates, and a {@code source:} without table/date columns fail the load
     * — a calendar that cannot be evaluated must not wait for its first firing to say so.
     * Declaring both {@code dates:} and {@code source:} is left to lint ({@code TQL-BATCH-4203})
     * so the whole app's findings surface together.
     */
    public static Calendars load(Path appHome, SimpleYamlParser parser) {
        Path dir = appHome.resolve("calendars");
        Map<String, CalendarsDocument.Calendar> calendars = new LinkedHashMap<>();
        Map<String, String> sources = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) {
            return new Calendars(calendars, sources);
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(file -> parser.parseCalendars(file).calendars()
                            .forEach((name, calendar) -> {
                                if (calendars.putIfAbsent(name, calendar) != null) {
                                    throw new TqlException(DUPLICATE, "Calendar '" + name
                                            + "' is declared twice (second: " + file + ")");
                                }
                                sources.put(name, appHome.relativize(file).toString()
                                        .replace('\\', '/'));
                            }));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        calendars.forEach(Calendars::validate);
        return new Calendars(calendars, sources);
    }

    private static void validate(String name, CalendarsDocument.Calendar calendar) {
        if (calendar == null) {
            throw new TqlException(INVALID, "Calendar '" + name + "' is empty");
        }
        weekend(calendar);
        if (calendar.holidays() == null) {
            return;
        }
        for (String date : calendar.holidays().dates()) {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException ex) {
                throw new TqlException(INVALID, "Calendar '" + name + "' holiday '" + date
                        + "' is not an ISO date (yyyy-MM-dd)");
            }
        }
        CalendarsDocument.Source source = calendar.holidays().source();
        if (source != null) {
            requireIdentifier(name, "source.table", source.table());
            requireIdentifier(name, "source.date", source.date());
            if (source.calendar() != null) {
                requireIdentifier(name, "source.calendar", source.calendar());
            }
        }
    }

    private static void requireIdentifier(String name, String key, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new TqlException(INVALID, "Calendar '" + name + "' " + key + " must be a"
                    + " plain identifier (was '" + value + "') — it becomes SQL text");
        }
    }

    public boolean isEmpty() {
        return calendars.isEmpty();
    }

    /** The declared calendars by name (for the fire-time gate, lint, and the docs portal). */
    public Map<String, CalendarsDocument.Calendar> calendars() {
        return calendars;
    }

    /** The app-relative document that declared a calendar (lint findings point at it). */
    public String sourceOf(String name) {
        return sources.getOrDefault(name, "calendars/");
    }

    /**
     * Whether a considered firing counts: the date is a business day under the calendar, and
     * — for the month-boundary qualifiers — no earlier/later business day exists in its month.
     * {@code runOn} null means {@code business-day}.
     */
    public static boolean counts(CalendarsDocument.Calendar calendar, String runOn,
            LocalDate date, Set<LocalDate> holidays) {
        Set<DayOfWeek> weekend = weekend(calendar);
        if (!isBusinessDay(date, weekend, holidays)) {
            return false;
        }
        return switch (runOn == null ? "business-day" : runOn) {
            case "first-business-day-of-month" -> date.withDayOfMonth(1).datesUntil(date)
                    .noneMatch(earlier -> isBusinessDay(earlier, weekend, holidays));
            case "last-business-day-of-month" -> date.plusDays(1)
                    .datesUntil(date.withDayOfMonth(date.lengthOfMonth()).plusDays(1))
                    .noneMatch(later -> isBusinessDay(later, weekend, holidays));
            default -> true;
        };
    }

    private static boolean isBusinessDay(LocalDate date, Set<DayOfWeek> weekend,
            Set<LocalDate> holidays) {
        return !weekend.contains(date.getDayOfWeek()) && !holidays.contains(date);
    }

    /**
     * The shifted-nominal-day rule ("the 5th, or the next business day when it is a holiday"):
     * whether {@code fireDate} is the shifted target of a month's nominal day, and if so
     * <em>which</em> nominal date the firing is for — the run's business date defaults to it,
     * because "the 5th's close, executed on the 7th" is about the 5th.
     *
     * <p>Stateless by construction: the shifted target is a pure function of the calendar, so
     * no missed-date memory exists anywhere. A shift can cross a month boundary in either
     * direction (the 31st shifting forward into the 1st; the 1st shifting back into the 30th),
     * so the neighbouring month's nominal day is checked too. {@code dayOfMonth} beyond the
     * month's length rounds down to its last day (the "31st" of April is the 30th).
     *
     * @return the nominal date the firing is for, or null when the fire date does not count
     */
    public static LocalDate shiftedNominal(CalendarsDocument.Calendar calendar, int dayOfMonth,
            String shift, LocalDate fireDate, Set<LocalDate> holidays) {
        Set<DayOfWeek> weekend = weekend(calendar);
        boolean previous = "previous-business-day".equals(shift);
        // Forward shifts can land in the month after their nominal day; backward shifts in the
        // month before. Check the fire month and the one neighbour that can reach it.
        LocalDate[] months = {fireDate.withDayOfMonth(1),
                previous
                        ? fireDate.withDayOfMonth(1).plusMonths(1)
                        : fireDate.withDayOfMonth(1).minusMonths(1)};
        for (LocalDate month : months) {
            LocalDate nominal = month
                    .withDayOfMonth(Math.min(dayOfMonth, month.lengthOfMonth()));
            LocalDate target = nominal;
            // A calendar with no business days at all must terminate: give up after a year.
            for (int step = 0; step < 366 && !isBusinessDay(target, weekend, holidays); step++) {
                target = previous ? target.minusDays(1) : target.plusDays(1);
            }
            if (isBusinessDay(target, weekend, holidays) && target.equals(fireDate)) {
                return nominal;
            }
        }
        return null;
    }

    /** The calendar's fixed {@code dates:} as parsed dates (validated at load). */
    public static Set<LocalDate> staticHolidays(CalendarsDocument.Calendar calendar) {
        if (calendar.holidays() == null) {
            return Set.of();
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        calendar.holidays().dates().forEach(date -> dates.add(LocalDate.parse(date)));
        return dates;
    }

    /**
     * Reads a table-backed calendar's holiday rows (all of them — holiday tables are small and
     * the month-boundary qualifiers need the neighbours). Identifiers were validated at load.
     */
    public static Set<LocalDate> readHolidays(Connection connection, String calendarName,
            CalendarsDocument.Source source) throws SQLException {
        String sql = "select " + source.date() + " from " + source.table()
                + (source.calendar() == null ? "" : " where " + source.calendar() + " = ?");
        Set<LocalDate> holidays = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (source.calendar() != null) {
                statement.setString(1, calendarName);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    java.sql.Date date = rows.getDate(1);
                    if (date != null) {
                        holidays.add(date.toLocalDate());
                    }
                }
            }
        }
        return holidays;
    }

    private static Set<DayOfWeek> weekend(CalendarsDocument.Calendar calendar) {
        List<String> declared = calendar.weekend();
        if (declared == null) {
            return DEFAULT_WEEKEND;
        }
        Set<DayOfWeek> weekend = java.util.EnumSet.noneOf(DayOfWeek.class);
        for (String day : declared) {
            try {
                weekend.add(DayOfWeek.valueOf(day.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new TqlException(INVALID,
                        "Unknown weekend day '" + day + "' — use monday…sunday");
            }
        }
        return weekend;
    }
}
