package io.tesseraql.runtime;

import io.tesseraql.yaml.calendar.Calendars;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.CalendarsDocument;
import io.tesseraql.yaml.model.TriggerSpec;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;

/**
 * The one place a job's business-day calendar is applied to a date (docs/jobs.md): the
 * scheduling gate asks "does this firing count, and which date is it for", and the console's
 * jobs page asks "when does the next firing count" — both answers must come from the same
 * arithmetic or they will drift. Resolution failures fail open, the fire-time stance.
 */
final class CalendarDecisions {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(CalendarDecisions.class);

    /** How far ahead the console preview searches — covers a monthly nominal-day rule. */
    private static final int PREVIEW_HORIZON_DAYS = 62;

    private final Calendars calendars;
    private final DataSource mainDataSource;
    private final Map<String, ? extends DataSource> namedDataSources;

    CalendarDecisions(Calendars calendars, DataSource mainDataSource,
            Map<String, ? extends DataSource> namedDataSources) {
        this.calendars = calendars;
        this.mainDataSource = mainDataSource;
        this.namedDataSources = namedDataSources;
    }

    /** Whether a firing on {@code fireDate} counts, and the nominal date it is for. */
    SchedulingRouteBuilder.CalendarGate.Decision decide(JobFile job, LocalDate fireDate) {
        Resolved resolved = resolve(job);
        if (resolved == null) {
            return SchedulingRouteBuilder.CalendarGate.Decision.RUNS;
        }
        TriggerSpec.Schedule schedule = resolved.schedule();
        if (schedule.dayOfMonth() != null) {
            LocalDate nominal = Calendars.shiftedNominal(resolved.calendar(),
                    schedule.dayOfMonth(), schedule.shift(), fireDate, resolved.holidays());
            return nominal == null
                    ? SchedulingRouteBuilder.CalendarGate.Decision.FILTERED
                    : new SchedulingRouteBuilder.CalendarGate.Decision(true, nominal);
        }
        return Calendars.counts(resolved.calendar(), schedule.runOn(), fireDate,
                resolved.holidays())
                        ? SchedulingRouteBuilder.CalendarGate.Decision.RUNS
                        : SchedulingRouteBuilder.CalendarGate.Decision.FILTERED;
    }

    /**
     * The console preview: the next date (from {@code from}, inclusive) the calendar lets a
     * firing count — "8/3 (for 7/31)" when a shifted nominal day is involved — or null when
     * the schedule has no calendar, nothing counts within the horizon, or resolution failed.
     * The daily-consider model is invisible until its outcome is shown somewhere.
     */
    String nextCounting(JobFile job, LocalDate from) {
        Resolved resolved = resolve(job);
        if (resolved == null) {
            return null;
        }
        for (int day = 0; day <= PREVIEW_HORIZON_DAYS; day++) {
            LocalDate candidate = from.plusDays(day);
            TriggerSpec.Schedule schedule = resolved.schedule();
            if (schedule.dayOfMonth() != null) {
                LocalDate nominal = Calendars.shiftedNominal(resolved.calendar(),
                        schedule.dayOfMonth(), schedule.shift(), candidate,
                        resolved.holidays());
                if (nominal != null) {
                    return nominal.equals(candidate)
                            ? candidate.toString()
                            : candidate + " (for " + nominal + ")";
                }
            } else if (Calendars.counts(resolved.calendar(), schedule.runOn(), candidate,
                    resolved.holidays())) {
                return candidate.toString();
            }
        }
        return null;
    }

    private record Resolved(TriggerSpec.Schedule schedule, CalendarsDocument.Calendar calendar,
            Set<LocalDate> holidays) {
    }

    /** Null when the job has no calendar qualifier or the calendar cannot be resolved. */
    private Resolved resolve(JobFile job) {
        TriggerSpec trigger = job.definition().trigger();
        TriggerSpec.Schedule schedule = trigger == null ? null : trigger.schedule();
        if (schedule == null || schedule.calendar() == null || schedule.calendar().isBlank()) {
            return null;
        }
        CalendarsDocument.Calendar calendar = calendars.calendars().get(schedule.calendar());
        if (calendar == null) {
            LOG.warn("Job {} names unknown calendar '{}'; firing runs unfiltered",
                    job.definition().id(), schedule.calendar());
            return null;
        }
        try {
            Set<LocalDate> holidays;
            if (calendar.holidays() != null && calendar.holidays().source() != null) {
                String declared = job.definition().datasource();
                DataSource pool = declared == null || declared.isBlank()
                        || "main".equals(declared)
                                ? mainDataSource
                                : namedDataSources.get(declared);
                try (java.sql.Connection connection = pool.getConnection()) {
                    holidays = Calendars.readHolidays(connection, schedule.calendar(),
                            calendar.holidays().source());
                }
            } else {
                holidays = Calendars.staticHolidays(calendar);
            }
            return new Resolved(schedule, calendar, holidays);
        } catch (Exception ex) {
            LOG.warn("Job {} calendar '{}' holiday read failed; firing runs unfiltered",
                    job.definition().id(), schedule.calendar(), ex);
            return null;
        }
    }
}
