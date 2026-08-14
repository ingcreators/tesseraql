package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.Declarations.Located;
import io.tesseraql.studio.StudioService.CalendarDayCell;
import io.tesseraql.studio.StudioService.CalendarEditState;
import io.tesseraql.studio.StudioService.CalendarMonthGrid;
import io.tesseraql.studio.StudioService.CalendarSummary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The calendars the Studio edits: the business-day month grid a reader clicks, and the weekend,
 * holiday and table-backed settings written back as a draft.
 *
 * <p>Extracted from {@code StudioService}; the records the view renders stay declared there.
 */
final class CalendarForms {

    /** TQL-STUDIO-4238: a calendar edit that cannot mean anything (docs/jobs.md). */
    private static final TqlErrorCode CALENDAR_EDIT = new TqlErrorCode(TqlDomain.STUDIO, 4238);

    private final Declarations declarations;

    CalendarForms(Declarations declarations) {
        this.declarations = declarations;
    }

    /** The draft-aware locate over {@code calendars/*.yml} ({@code calendars:} documents). */
    private Located locateCalendar(String name) {
        return declarations.locate("calendars", "calendars", name);
    }

    /**
     * The declared business-day calendars (docs/jobs.md "Business-day calendars"), sorted —
     * name, declaring file, the effective weekend, and where the holidays live: a fixed
     * {@code dates:} list the form edits, or a table the data browser owns.
     */
    List<CalendarSummary> calendars() {
        var declared = io.tesseraql.yaml.calendar.Calendars.load(declarations.appHome(),
                declarations.parser());
        List<CalendarSummary> out = new ArrayList<>();
        declared.calendars().forEach((name, calendar) -> {
            boolean tableBacked = calendar.holidays() != null
                    && calendar.holidays().source() != null;
            out.add(new CalendarSummary(name, declared.sourceOf(name),
                    calendar.weekend() == null
                            ? List.of("saturday", "sunday")
                            : List.copyOf(calendar.weekend()),
                    tableBacked,
                    tableBacked ? calendar.holidays().source().table() : null,
                    calendar.holidays() == null
                            ? List.of()
                            : List.copyOf(calendar.holidays().dates())));
        });
        out.sort(java.util.Comparator.comparing(CalendarSummary::name));
        return out;
    }

    /**
     * The month grid (docs/batch-platform.md track B, Studio): one month of the calendar with
     * each day classified — business, weekend, holiday — and, when a nominal-day rule is
     * being previewed, the nominal date and its shifted landing highlighted. The
     * daily-consider model is invisible until its outcome is drawn somewhere; this is where.
     * Table-backed holidays arrive resolved by the caller (the runtime reads the main
     * datasource); a null set renders the grid without holiday knowledge, flagged as such.
     */
    CalendarMonthGrid calendarMonth(String name, String month, Integer dayOfMonth,
            String shift, java.util.Set<java.time.LocalDate> tableHolidays) {
        var declared = io.tesseraql.yaml.calendar.Calendars.load(declarations.appHome(),
                declarations.parser());
        var calendar = declared.calendars().get(name);
        if (calendar == null) {
            return new CalendarMonthGrid(name, null, null, null, null, List.of(), false,
                    dayOfMonth, shift,
                    "No calendar named '" + name + "' is declared under calendars/");
        }
        boolean tableBacked = calendar.holidays() != null
                && calendar.holidays().source() != null;
        java.util.Set<java.time.LocalDate> holidays = tableBacked
                ? (tableHolidays == null ? java.util.Set.of() : tableHolidays)
                : io.tesseraql.yaml.calendar.Calendars.staticHolidays(calendar);
        java.time.YearMonth yearMonth;
        try {
            yearMonth = month == null || month.isBlank()
                    ? java.time.YearMonth.now()
                    : java.time.YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException badMonth) {
            yearMonth = java.time.YearMonth.now();
        }
        java.time.LocalDate nominal = dayOfMonth == null
                ? null
                : yearMonth.atDay(Math.min(dayOfMonth, yearMonth.lengthOfMonth()));
        java.time.LocalDate cursor = yearMonth.atDay(1);
        while (cursor.getDayOfWeek() != java.time.DayOfWeek.MONDAY) {
            cursor = cursor.minusDays(1);
        }
        List<List<CalendarDayCell>> weeks = new ArrayList<>();
        while (!java.time.YearMonth.from(cursor).isAfter(yearMonth)) {
            List<CalendarDayCell> week = new ArrayList<>();
            for (int day = 0; day < 7; day++) {
                boolean inMonth = java.time.YearMonth.from(cursor).equals(yearMonth);
                boolean business = io.tesseraql.yaml.calendar.Calendars.counts(calendar, null,
                        cursor, holidays);
                boolean holiday = holidays.contains(cursor);
                boolean lands = dayOfMonth != null
                        && io.tesseraql.yaml.calendar.Calendars.shiftedNominal(calendar,
                                dayOfMonth, shift, cursor, holidays) != null;
                week.add(new CalendarDayCell(cursor.getDayOfMonth(), inMonth, business,
                        !business && !holiday, holiday, cursor.equals(nominal), lands));
                cursor = cursor.plusDays(1);
            }
            weeks.add(week);
        }
        return new CalendarMonthGrid(name, yearMonth.toString(), yearMonth.getMonth() + " "
                + yearMonth.getYear(), yearMonth.minusMonths(1).toString(),
                yearMonth.plusMonths(1).toString(), weeks, tableBacked, dayOfMonth, shift,
                null);
    }

    /**
     * Saves a calendar's weekend and fixed holiday dates through the draft flow
     * (docs/jobs.md). A table-backed calendar refuses the form — its rows are data, owned by
     * the data browser — and a name not yet declared becomes a new {@code calendars/} draft.
     * Invalid day names and dates die here with {@code TQL-STUDIO-4238} instead of landing
     * in a draft the manifest load would reject.
     */
    Path saveCalendar(String name, List<String> weekend, List<String> dates,
            String actor) {
        if (declarations.readOnly()) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing calendars is"
                            + " disabled");
        }
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new TqlException(CALENDAR_EDIT,
                    "Calendar name '" + name + "' is not a plain identifier");
        }
        for (String day : weekend) {
            try {
                java.time.DayOfWeek.valueOf(day.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new TqlException(CALENDAR_EDIT,
                        "Unknown weekend day '" + day + "' — use monday…sunday");
            }
        }
        List<String> cleanDates = new ArrayList<>();
        for (String date : dates) {
            String trimmed = StudioService.trimToNull(date);
            if (trimmed == null) {
                continue;
            }
            try {
                java.time.LocalDate.parse(trimmed);
            } catch (java.time.format.DateTimeParseException ex) {
                throw new TqlException(CALENDAR_EDIT,
                        "Holiday '" + trimmed + "' is not an ISO date (yyyy-MM-dd)");
            }
            cleanDates.add(trimmed);
        }
        Located located = locateCalendar(name);
        Map<String, Object> tree;
        String relative;
        Map<String, Object> node;
        if (located == null) {
            relative = "calendars/" + name + ".yml";
            tree = new LinkedHashMap<>();
            tree.put("version", "tesseraql/v1");
            Map<String, Object> calendars = new LinkedHashMap<>();
            node = new LinkedHashMap<>();
            calendars.put(name, node);
            tree.put("calendars", calendars);
        } else {
            relative = located.path();
            tree = located.tree();
            node = located.node();
            if (StudioService.anyMap(node.get("holidays")).get("source") != null) {
                throw new TqlException(CALENDAR_EDIT, "Calendar '" + name + "' is table-backed"
                        + " — its holiday rows are data (edit them in the data browser)");
            }
        }
        node.put("weekend", new ArrayList<>(weekend));
        if (cleanDates.isEmpty()) {
            node.remove("holidays");
        } else {
            Map<String, Object> holidays = new LinkedHashMap<>();
            holidays.put("dates", cleanDates);
            node.put("holidays", holidays);
        }
        Path draft = declarations.saveDraft(relative, declarations.parser().write(tree));
        declarations.audit(actor, "calendar", name);
        return draft;
    }

    /**
     * The edit card's draft-aware view of a calendar (docs/studio-ux-refresh.md slice 6):
     * weekend and holiday dates as the PENDING draft has them when one exists, else as the
     * source declares them — so click-to-toggle edits accumulate visibly before the apply.
     * {@code null} when no such calendar is declared.
     */
    CalendarEditState calendarEditState(String name) {
        Located located = locateCalendar(name);
        if (located == null) {
            return null;
        }
        Map<String, Object> holidays = StudioService.anyMap(located.node().get("holidays"));
        boolean tableBacked = holidays.get("source") != null;
        List<String> weekend = new ArrayList<>();
        if (located.node().get("weekend") instanceof List<?> declared) {
            declared.forEach(day -> weekend.add(String.valueOf(day)));
        } else {
            weekend.addAll(List.of("saturday", "sunday"));
        }
        List<String> dates = new ArrayList<>();
        if (holidays.get("dates") instanceof List<?> declared) {
            declared.forEach(date -> dates.add(String.valueOf(date)));
        }
        dates.sort(null);
        return new CalendarEditState(weekend, dates, tableBacked,
                declarations.readDraft(located.path()) != null);
    }

    /**
     * Toggles one holiday date on a calendar (slice 6, the edit card's hc-calendar
     * click-to-toggle): present is removed, absent is added, and the result lands through
     * {@link #saveCalendar} — the same validation and draft flow as the form save, so
     * table-backed calendars refuse and successive toggles accumulate on the pending draft.
     */
    Path toggleCalendarHoliday(String name, String date, String actor) {
        String clean = StudioService.trimToNull(date);
        if (clean == null) {
            throw new TqlException(CALENDAR_EDIT, "A holiday toggle needs a date");
        }
        CalendarEditState current = calendarEditState(name);
        if (current == null) {
            throw new TqlException(CALENDAR_EDIT,
                    "No calendar named '" + name + "' is declared");
        }
        List<String> dates = new ArrayList<>(current.dates());
        if (!dates.remove(clean)) {
            dates.add(clean);
            dates.sort(null);
        }
        return saveCalendar(name, current.weekend(), dates, actor);
    }

}
