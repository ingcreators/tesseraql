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
 * arithmetic or they will drift.
 *
 * <p>Resolution failures fail open — a calendar the runtime cannot read must not strand a
 * scheduled job — but the fail-open is <em>recorded</em>, not merely logged: a job that should
 * have been filtered out ran on a holiday, and one WARN line was the only trace
 * (docs/silent-tolerance.md O5). {@link io.tesseraql.opsui.CalendarStatus} carries it to the
 * dashboard, which raises {@code TQL-OPS-9009} while any job is firing unfiltered.
 */
final class CalendarDecisions {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory
            .getLogger(CalendarDecisions.class);

    /** How far ahead the console preview searches — covers a monthly nominal-day rule. */
    private static final int PREVIEW_HORIZON_DAYS = 62;

    private final Calendars calendars;
    private final DataSource mainDataSource;
    private final Map<String, ? extends DataSource> namedDataSources;
    private io.tesseraql.opsui.CalendarStatus status;

    CalendarDecisions(Calendars calendars, DataSource mainDataSource,
            Map<String, ? extends DataSource> namedDataSources) {
        this.calendars = calendars;
        this.mainDataSource = mainDataSource;
        this.namedDataSources = namedDataSources;
    }

    /** Wires the registry that carries a fail-open to the operations dashboard. */
    CalendarDecisions status(io.tesseraql.opsui.CalendarStatus status) {
        this.status = status;
        return this;
    }

    /** Records a firing that ran unfiltered, so the gate's absence is observable, not implied. */
    private void failOpen(JobFile job, String calendar, String reason) {
        LOG.warn("Job {} calendar '{}' could not be resolved ({}); firing runs unfiltered",
                job.definition().id(), calendar, reason);
        if (status != null) {
            status.failedOpen(job.definition().id(), calendar, reason);
        }
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
            failOpen(job, schedule.calendar(), "no calendar of that name is declared");
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
            if (status != null) {
                // It resolves again: a fixed config stops alerting without a restart.
                status.resolved(job.definition().id());
            }
            return new Resolved(schedule, calendar, holidays);
        } catch (Exception ex) {
            failOpen(job, schedule.calendar(), "holiday read failed: " + ex.getMessage());
            return null;
        }
    }
}
