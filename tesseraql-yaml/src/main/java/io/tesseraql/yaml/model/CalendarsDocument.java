package io.tesseraql.yaml.model;

import java.util.List;
import java.util.Map;

/**
 * One parsed {@code calendars/*.yml} document (docs/batch-platform.md track B): named
 * business-day calendars — a weekend definition plus holidays, either a small fixed
 * {@code dates:} list or a table-backed {@code source:} operations maintains as rows.
 * Aggregation into the app-wide namespace lives in
 * {@link io.tesseraql.yaml.calendar.Calendars}.
 *
 * @param version   the DSL version, {@code tesseraql/v1}
 * @param calendars declared calendars by name
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record CalendarsDocument(String version, Map<String, Calendar> calendars) {

    public CalendarsDocument {
        calendars = calendars == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(calendars));
    }

    /**
     * One business-day calendar.
     *
     * @param weekend  weekly non-business days as lowercase day names; null means the
     *                 Saturday/Sunday default, an explicit empty list means none
     * @param holidays the calendar's holidays, when declared
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Calendar(List<String> weekend, Holidays holidays) {
    }

    /**
     * A calendar's holidays: exactly one home for the rows — a fixed {@code dates:} list or a
     * table-backed {@code source:} read at fire time (declaring both is a lint error).
     *
     * @param dates  fixed ISO dates, for small closed sets
     * @param source a holiday table read on the job's datasource at fire time
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Holidays(List<String> dates, Source source) {

        public Holidays {
            dates = dates == null ? List.of() : List.copyOf(dates);
        }
    }

    /**
     * A table-backed holiday source (the decision tables' {@code source:} precedent).
     *
     * @param table    the holiday table name
     * @param date     the date column
     * @param calendar the optional calendar-id column; when present, rows are filtered to the
     *                 declaring calendar's name
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String table, String date, String calendar) {
    }
}
