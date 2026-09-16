package io.tesseraql.test;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.WorkflowFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.EnrichSpec;
import io.tesseraql.yaml.model.PipelineStep;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.TransitionSpec;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Every 2-way SQL file an application's documents bind — the population a coverage run is
 * accountable for (docs/audit-low-leads.md G16). The gate and the regression aggregate used to
 * count only the files a case rendered, so a bound file nobody tested sat outside both, deleting a
 * test raised coverage, and an app whose suites never ran scored 100%.
 *
 * <p>Routes, consumers, tools and resources contribute their sources and steps (each binding's
 * file and its enrichment files), their validation {@code file:} rules and an export's
 * {@code after:} statement; a job its pipeline steps' files, enrichments and chunk reader/writer;
 * a workflow its transitions' command and guard files. A {@code contract:} or {@code service:}
 * binding carries no file and is not here; a file that does not exist or does not parse is
 * lint's finding and is skipped by the caller.
 */
public final class ManifestSqlFiles {

    private ManifestSqlFiles() {
    }

    /** The absolute, normalized paths, in manifest order. */
    public static Set<Path> of(AppManifest manifest) {
        Set<Path> files = new LinkedHashSet<>();
        manifest.routes().forEach(r -> document(r.source(), r.definition(), files));
        manifest.consumers().forEach(c -> document(c.source(), c.definition(), files));
        manifest.tools().forEach(t -> document(t.source(), t.definition(), files));
        manifest.resources().forEach(r -> document(r.source(), r.definition(), files));
        manifest.jobs().forEach(job -> job(job, files));
        manifest.workflows().forEach(workflow -> workflow(workflow, files));
        return files;
    }

    private static void document(Path source, RouteDefinition definition, Set<Path> files) {
        Path dir = source.getParent();
        definition.sources().values().forEach(binding -> binding(dir, binding, files));
        definition.steps().values().forEach(binding -> binding(dir, binding, files));
        definition.validate().values().forEach(rule -> add(dir, rule.file(), files));
        if (definition.fileExport() != null && definition.fileExport().after() != null
                && definition.fileExport().after().sql() != null) {
            add(dir, definition.fileExport().after().sql().file(), files);
        }
    }

    private static void job(JobFile job, Set<Path> files) {
        Path dir = job.source().getParent();
        for (PipelineStep step : job.definition().effectiveSteps()) {
            binding(dir, step.sql(), files);
            if (step.chunk() != null) {
                binding(dir, step.chunk().reader(), files);
                binding(dir, step.chunk().writer(), files);
            }
        }
    }

    private static void workflow(WorkflowFile workflow, Set<Path> files) {
        Path dir = workflow.source().getParent();
        for (TransitionSpec transition : workflow.definition().transitions()) {
            add(dir, transition.commandFile(), files);
            if (transition.guard() != null) {
                add(dir, transition.guard().file(), files);
            }
        }
    }

    /** A binding's own file, then every enrichment reference hanging off it. */
    private static void binding(Path dir, Binding binding, Set<Path> files) {
        if (binding == null) {
            return;
        }
        add(dir, binding.file(), files);
        if (binding.enrich() != null) {
            for (Map.Entry<String, EnrichSpec> enrich : binding.enrich().entrySet()) {
                if (enrich.getValue().sql() != null) {
                    add(dir, enrich.getValue().sql().file(), files);
                }
            }
        }
    }

    private static void add(Path dir, String file, Set<Path> files) {
        if (file != null && !file.isBlank()) {
            files.add(dir.resolve(file).toAbsolutePath().normalize());
        }
    }
}
