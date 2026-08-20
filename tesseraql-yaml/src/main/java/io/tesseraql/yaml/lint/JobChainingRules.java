package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Job chaining ({@code after:}) across the app's jobs.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class JobChainingRules implements LintRule {

    private static final String INVALID_JOB_CHAIN = "TQL-BATCH-4209";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintJobChaining(context.appHome(), manifest, findings);
    }

    /**
     * Statically checks {@code trigger: after:} chaining (docs/batch-platform.md track D): the
     * named job must exist ({@code TQL-BATCH-4209}), a chain must not loop (the runtime's
     * fired-set would silently drop the repeat — the declaration is the mistake), and a
     * trigger declares one kind: {@code after:} does not combine with a schedule or a poll.
     */
    void lintJobChaining(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        Map<String, String> parents = new java.util.LinkedHashMap<>();
        java.util.Set<String> jobIds = new java.util.LinkedHashSet<>();
        manifest.jobs().forEach(job -> jobIds.add(job.definition().id()));
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            io.tesseraql.yaml.model.TriggerSpec trigger = job.definition().trigger();
            if (trigger == null || trigger.after() == null || trigger.after().isBlank()) {
                continue;
            }
            String source = appHome.relativize(job.source()).toString().replace('\\', '/');
            String jobId = job.definition().id();
            if (trigger.schedule() != null || trigger.poll() != null) {
                findings.add(
                        new LintFinding(LintCodes.INVALID_JOB_TRIGGER, ERROR, source,
                                "Job '" + jobId + "' declares after: together with another trigger"
                                        + " kind; declare one"));
            }
            if (!jobIds.contains(trigger.after())) {
                findings.add(new LintFinding(INVALID_JOB_CHAIN, ERROR, source,
                        "Job '" + jobId + "' chains after unknown job '" + trigger.after()
                                + "' — it would never fire"));
                continue;
            }
            parents.put(jobId, trigger.after());
        }
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            String jobId = job.definition().id();
            java.util.Set<String> walked = new java.util.LinkedHashSet<>();
            String current = jobId;
            while (parents.containsKey(current) && walked.add(current)) {
                current = parents.get(current);
            }
            if (parents.containsKey(current) && current.equals(jobId)) {
                findings.add(new LintFinding(INVALID_JOB_CHAIN, ERROR,
                        appHome.relativize(job.source()).toString().replace('\\', '/'),
                        "Job '" + jobId + "' is part of an after: cycle (" + walked
                                + ") — a chain must end"));
            }
        }
    }
}
