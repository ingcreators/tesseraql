package io.tesseraql.yaml.lint;

import java.util.List;

/**
 * A job's overlap policy and SLA/deadline declarations.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class OverlapSlaRules {

    private OverlapSlaRules() {
    }

    /**
     * Statically checks {@code overlap:} and {@code sla:} (docs/batch-platform.md track E):
     * both are operational promises evaluated long after the deploy, so a value that cannot
     * mean anything — an unknown overlap policy, a deadline that does not parse — must be a
     * build error, not a sweep that silently never fires ({@code TQL-BATCH-4210}).
     */
    static void lintOverlapAndSla(io.tesseraql.yaml.manifest.JobFile job, String source,
            List<LintFinding> findings) {
        String jobId = job.definition().id();
        String overlap = job.definition().overlap();
        if (overlap != null && !List.of("concurrent", "skip").contains(overlap)) {
            findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                    + "' overlap '" + overlap + "' is not one of concurrent, skip"));
        }
        io.tesseraql.yaml.model.SlaSpec sla = job.definition().sla();
        if (sla == null) {
            return;
        }
        if ((sla.completeBy() == null || sla.completeBy().isBlank())
                && (sla.runningLongerThan() == null || sla.runningLongerThan().isBlank())) {
            findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                    + "' declares sla: without completeBy: or runningLongerThan:"));
        }
        if (sla.completeBy() != null && !sla.completeBy().isBlank()) {
            try {
                java.time.LocalTime.parse(sla.completeBy());
            } catch (java.time.format.DateTimeParseException ex) {
                findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                        + "' sla completeBy '" + sla.completeBy()
                        + "' is not a wall-clock time (HH:mm)"));
            }
        }
        if (sla.runningLongerThan() != null && !sla.runningLongerThan().isBlank()) {
            try {
                io.tesseraql.core.util.Durations.toMillis(sla.runningLongerThan());
            } catch (RuntimeException ex) {
                findings.add(new LintFinding("TQL-BATCH-4210", "error", source, "Job '" + jobId
                        + "' sla runningLongerThan '" + sla.runningLongerThan()
                        + "' is not a duration (e.g. 2h, 30m)"));
            }
        }
    }
}
