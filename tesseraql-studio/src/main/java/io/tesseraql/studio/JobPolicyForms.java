package io.tesseraql.studio;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService.JobPolicyForm;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A declared job's trigger and its operational promises, as a form (docs/jobs.md).
 *
 * <p>Unlike the decision and calendar editors it does not locate a <em>declaration</em>: a job is
 * one document, found through the manifest by its id, so the shared walk does not apply. The read,
 * the draft write and the audit call are the same, which is what {@link Declarations} carries.
 *
 * <p>Poll-triggered jobs keep the text editor: the poll block carries connector security a form
 * would blur.
 */
final class JobPolicyForms {

    /** TQL-STUDIO-4239: a job-policy edit that cannot mean anything (docs/jobs.md). */
    private static final TqlErrorCode JOB_POLICY = new TqlErrorCode(TqlDomain.STUDIO, 4239);

    private final Declarations declarations;
    private final Supplier<AppManifest> manifest;

    JobPolicyForms(Declarations declarations, Supplier<AppManifest> manifest) {
        this.declarations = declarations;
        this.manifest = manifest;
    }

    /**
     * The job-policies form (docs/jobs.md, Studio): a declared job's trigger — schedule with
     * its calendar qualifiers, or an {@code after:} chain — plus the operational promises
     * ({@code overlap:}, {@code sla:}) as structured fields. Poll-triggered jobs keep the
     * text editor: the poll block carries connector security the form must not blur.
     */
    JobPolicyForm jobPolicyForm(String jobId) {
        LocatedJob located = locateJob(jobId);
        if (located == null) {
            return null;
        }
        Map<String, Object> trigger = StudioService.anyMap(located.tree().get("trigger"));
        Map<String, Object> schedule = StudioService.anyMap(trigger.get("schedule"));
        Map<String, Object> sla = StudioService.anyMap(located.tree().get("sla"));
        return new JobPolicyForm(jobId, located.path(),
                trigger.get("poll") != null,
                StudioService.scalar(schedule.get("cron")),
                StudioService.scalar(schedule.get("fixedDelay")),
                StudioService.scalar(schedule.get("calendar")),
                StudioService.scalar(schedule.get("runOn")),
                StudioService.scalar(schedule.get("dayOfMonth")),
                StudioService.scalar(schedule.get("shift")),
                StudioService.scalar(trigger.get("after")),
                StudioService.scalar(located.tree().get("overlap")),
                StudioService.scalar(sla.get("completeBy")),
                StudioService.scalar(sla.get("runningLongerThan")));
    }

    /**
     * Applies the job-policy form onto the job document and lands it as a draft. Every
     * declaration that cannot mean anything dies here with {@code TQL-STUDIO-4239} — the
     * same rules the linter enforces, answered before a draft exists: one trigger kind, one
     * schedule cadence, one calendar qualifier, known enum values, parseable SLA fields.
     */
    Path saveJobPolicies(String jobId, String cron, String fixedDelay, String calendar,
            String runOn, String dayOfMonth, String shift, String after, String overlap,
            String slaCompleteBy, String slaRunningLongerThan, String actor) {
        if (declarations.readOnly()) {
            throw new TqlException(StudioService.READ_ONLY,
                    "Studio is read-only; editing job policies is"
                            + " disabled");
        }
        LocatedJob located = locateJob(jobId);
        if (located == null) {
            throw new TqlException(StudioService.NOT_FOUND,
                    "No job named '" + jobId + "' is declared under batch/");
        }
        if (StudioService.anyMap(located.tree().get("trigger")).get("poll") != null) {
            throw new TqlException(JOB_POLICY, "Job '" + jobId + "' is poll-triggered — edit"
                    + " its trigger in the text editor (the poll block carries connector"
                    + " security)");
        }
        cron = StudioService.trimToNull(cron);
        fixedDelay = StudioService.trimToNull(fixedDelay);
        calendar = StudioService.trimToNull(calendar);
        runOn = StudioService.trimToNull(runOn);
        dayOfMonth = StudioService.trimToNull(dayOfMonth);
        shift = StudioService.trimToNull(shift);
        after = StudioService.trimToNull(after);
        overlap = StudioService.trimToNull(overlap);
        slaCompleteBy = StudioService.trimToNull(slaCompleteBy);
        slaRunningLongerThan = StudioService.trimToNull(slaRunningLongerThan);

        boolean scheduled = cron != null || fixedDelay != null;
        if (after != null && scheduled) {
            throw new TqlException(JOB_POLICY,
                    "A trigger declares one kind: after: or a schedule, not both");
        }
        if (cron != null && fixedDelay != null) {
            throw new TqlException(JOB_POLICY, "Declare cron or fixedDelay, not both");
        }
        if (!scheduled && (calendar != null || runOn != null || dayOfMonth != null
                || shift != null)) {
            throw new TqlException(JOB_POLICY,
                    "Calendar qualifiers need a schedule to qualify");
        }
        if (runOn != null && dayOfMonth != null) {
            throw new TqlException(JOB_POLICY,
                    "runOn and dayOfMonth are mutually exclusive — one qualifier decides");
        }
        if ((runOn != null || dayOfMonth != null || shift != null) && calendar == null) {
            throw new TqlException(JOB_POLICY, "runOn/dayOfMonth/shift qualify a calendar");
        }
        if (runOn != null && !io.tesseraql.yaml.calendar.Calendars.RUN_ON.contains(runOn)) {
            throw new TqlException(JOB_POLICY, "runOn '" + runOn + "' is not one of "
                    + new java.util.TreeSet<>(io.tesseraql.yaml.calendar.Calendars.RUN_ON));
        }
        if (shift != null
                && !io.tesseraql.yaml.calendar.Calendars.SHIFTS.contains(shift)) {
            throw new TqlException(JOB_POLICY, "shift '" + shift + "' is not one of "
                    + new java.util.TreeSet<>(io.tesseraql.yaml.calendar.Calendars.SHIFTS));
        }
        if (shift != null && dayOfMonth == null) {
            throw new TqlException(JOB_POLICY, "shift moves a nominal day — set dayOfMonth");
        }
        Integer day = null;
        if (dayOfMonth != null) {
            try {
                day = Integer.parseInt(dayOfMonth);
            } catch (NumberFormatException ex) {
                throw new TqlException(JOB_POLICY, "dayOfMonth '" + dayOfMonth
                        + "' is not a number");
            }
            if (day < 1 || day > 31) {
                throw new TqlException(JOB_POLICY, "dayOfMonth " + day + " is outside 1-31");
            }
        }
        if (calendar != null && !io.tesseraql.yaml.calendar.Calendars
                .load(declarations.appHome(), declarations.parser())
                .calendars().containsKey(calendar)) {
            throw new TqlException(JOB_POLICY, "Calendar '" + calendar
                    + "' is not declared under calendars/");
        }
        if (after != null && locateJob(after) == null) {
            throw new TqlException(JOB_POLICY,
                    "after: names unknown job '" + after + "'");
        }
        if (after != null && after.equals(jobId)) {
            throw new TqlException(JOB_POLICY, "A job cannot chain after itself");
        }
        if (overlap != null && !java.util.List.of("concurrent", "skip").contains(overlap)) {
            throw new TqlException(JOB_POLICY,
                    "overlap '" + overlap + "' is not one of concurrent, skip");
        }
        if (slaCompleteBy != null) {
            try {
                java.time.LocalTime.parse(slaCompleteBy);
            } catch (java.time.format.DateTimeParseException ex) {
                throw new TqlException(JOB_POLICY, "sla completeBy '" + slaCompleteBy
                        + "' is not a wall-clock time (HH:mm)");
            }
        }
        if (slaRunningLongerThan != null) {
            try {
                io.tesseraql.core.util.Durations.toMillis(slaRunningLongerThan);
            } catch (RuntimeException ex) {
                throw new TqlException(JOB_POLICY, "sla runningLongerThan '"
                        + slaRunningLongerThan + "' is not a duration (e.g. 2h, 30m)");
            }
        }

        Map<String, Object> tree = located.tree();
        if (after != null) {
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("after", after);
            tree.put("trigger", trigger);
        } else if (scheduled) {
            Map<String, Object> schedule = new LinkedHashMap<>();
            if (cron != null) {
                schedule.put("cron", cron);
            } else {
                schedule.put("fixedDelay", fixedDelay);
            }
            if (calendar != null) {
                schedule.put("calendar", calendar);
            }
            if (runOn != null) {
                schedule.put("runOn", runOn);
            }
            if (day != null) {
                schedule.put("dayOfMonth", day);
            }
            if (shift != null) {
                schedule.put("shift", shift);
            }
            Map<String, Object> trigger = new LinkedHashMap<>();
            trigger.put("schedule", schedule);
            tree.put("trigger", trigger);
        } else {
            tree.remove("trigger");
        }
        if (overlap == null || "concurrent".equals(overlap)) {
            tree.remove("overlap");
        } else {
            tree.put("overlap", overlap);
        }
        if (slaCompleteBy == null && slaRunningLongerThan == null) {
            tree.remove("sla");
        } else {
            Map<String, Object> sla = new LinkedHashMap<>();
            if (slaCompleteBy != null) {
                sla.put("completeBy", slaCompleteBy);
            }
            if (slaRunningLongerThan != null) {
                sla.put("runningLongerThan", slaRunningLongerThan);
            }
            tree.put("sla", sla);
        }
        Path draft = declarations.saveDraft(located.path(), declarations.parser().write(tree));
        declarations.audit(actor, "job-policies", jobId);
        return draft;
    }

    private record LocatedJob(String path, Map<String, Object> tree) {
    }

    /** The draft-aware locate: the batch document declaring {@code jobId}. */
    private LocatedJob locateJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return null;
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.get().jobs()) {
            if (!jobId.equals(job.definition().id())) {
                continue;
            }
            String relative = declarations.appHome().relativize(job.source()).toString()
                    .replace('\\', '/');
            String text = declarations.read(relative).text();
            if (text == null) {
                return null;
            }
            return new LocatedJob(relative, declarations.parser().parseTree(text));
        }
        return null;
    }

}
