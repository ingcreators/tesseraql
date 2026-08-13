package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;

/**
 * Business-day calendars and the schedule qualifiers that name them
 * (docs/batch-platform.md track B).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class CalendarRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        context.calendars(lintCalendars(context.appHome(), findings));
    }

    /**
     * Loads the app's business-day calendars for reference checking (docs/batch-platform.md
     * track B). A structurally broken calendars/ dir — duplicate names, bad dates, unknown day
     * names — surfaces as an error finding instead of aborting the whole lint, and a calendar
     * declaring both {@code dates:} and {@code source:} gets {@code TQL-BATCH-4203}: holiday
     * rows have exactly one home.
     */
    io.tesseraql.yaml.calendar.Calendars lintCalendars(Path appHome,
            List<LintFinding> findings) {
        io.tesseraql.yaml.calendar.Calendars calendars;
        try {
            calendars = io.tesseraql.yaml.calendar.Calendars.load(appHome,
                    new io.tesseraql.yaml.SimpleYamlParser());
        } catch (io.tesseraql.core.error.TqlException ex) {
            findings.add(new LintFinding(ex.code().toString(), "error", "calendars/",
                    ex.getMessage()));
            return io.tesseraql.yaml.calendar.Calendars.empty();
        }
        calendars.calendars().forEach((name, calendar) -> {
            if (calendar.holidays() != null && !calendar.holidays().dates().isEmpty()
                    && calendar.holidays().source() != null) {
                findings.add(new LintFinding("TQL-BATCH-4203", "error",
                        calendars.sourceOf(name), "Calendar '" + name
                                + "' declares both dates: and source: — holiday rows have"
                                + " exactly one home"));
            }
        });
        return calendars;
    }

    /**
     * Statically checks a schedule's calendar qualifiers (docs/batch-platform.md track B): the
     * named calendar must exist ({@code TQL-BATCH-4201}) and {@code runOn:} must ride on a
     * {@code calendar:} with a known value ({@code TQL-BATCH-4202}) — at fire time both fail
     * open, so the place to hear about a typo is the build.
     */
    static void lintScheduleCalendar(io.tesseraql.yaml.manifest.JobFile job,
            io.tesseraql.yaml.model.TriggerSpec.Schedule schedule,
            io.tesseraql.yaml.calendar.Calendars calendars, String source,
            List<LintFinding> findings) {
        String jobId = job.definition().id();
        boolean hasCalendar = schedule.calendar() != null && !schedule.calendar().isBlank();
        if (hasCalendar && !calendars.calendars().containsKey(schedule.calendar())) {
            findings.add(new LintFinding("TQL-BATCH-4201", "error", source,
                    "Job '" + jobId + "' schedule names unknown calendar '"
                            + schedule.calendar()
                            + "' — declare it under calendars/ or fix the reference"));
        }
        if (schedule.runOn() != null) {
            if (!hasCalendar) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares runOn: without calendar: —"
                                + " runOn qualifies a business-day calendar"));
            }
            if (!io.tesseraql.yaml.calendar.Calendars.RUN_ON.contains(schedule.runOn())) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule runOn '" + schedule.runOn()
                                + "' is not one of "
                                + new java.util.TreeSet<>(
                                        io.tesseraql.yaml.calendar.Calendars.RUN_ON)));
            }
        }
        if (schedule.dayOfMonth() != null) {
            if (!hasCalendar) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares dayOfMonth: without calendar:"
                                + " — the shift needs a business-day calendar"));
            }
            if (schedule.dayOfMonth() < 1 || schedule.dayOfMonth() > 31) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule dayOfMonth " + schedule.dayOfMonth()
                                + " is outside 1-31"));
            }
            if (schedule.runOn() != null) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares both runOn: and dayOfMonth: —"
                                + " one qualifier decides which firings count"));
            }
        }
        if (schedule.shift() != null) {
            if (schedule.dayOfMonth() == null) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule declares shift: without dayOfMonth: —"
                                + " a shift moves a nominal day"));
            }
            if (!io.tesseraql.yaml.calendar.Calendars.SHIFTS.contains(schedule.shift())) {
                findings.add(new LintFinding("TQL-BATCH-4202", "error", source,
                        "Job '" + jobId + "' schedule shift '" + schedule.shift()
                                + "' is not one of "
                                + new java.util.TreeSet<>(
                                        io.tesseraql.yaml.calendar.Calendars.SHIFTS)));
            }
        }
    }
}
