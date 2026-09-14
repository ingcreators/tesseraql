package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.DeclaredKinds;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.PipelineStep;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@code type:} a field declares, judged where it is declared (docs/temporal-semantics.md
 * T3): an {@code input:} whose type no request binds, a {@code result:} entry whose kind no
 * read parses or whose {@code format:} its parser refuses, a {@code result:} on a binding
 * that publishes no rows — all {@code TQL-YAML-1064}, reported from the predicate the
 * compiler refuses from — and a {@code result:} on a job's chunk reader or writer, which the
 * typed batch readers never apply (they keep the kind the read seam gives them), the same
 * code — and a job's {@code input:}, which binds through the route's binder, judged by the
 * route's predicate.
 */
final class DeclaredKindRules implements LintRule {

    private static final String UNSUPPORTED = DeclaredKinds.UNSUPPORTED.toString();

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        Path appHome = context.appHome();
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            String source = LintSupport.relative(appHome, document.getKey());
            RouteDefinition route = document.getValue();
            for (DeclaredKinds.Violation violation : DeclaredKinds.inputViolations(route)) {
                findings.add(new LintFinding(UNSUPPORTED, ERROR, source, violation.message(),
                        context.lineWithin(document.getKey(), "input:", token(violation)), null));
            }
            for (DeclaredKinds.Violation violation : DeclaredKinds.resultViolations(route)) {
                findings.add(new LintFinding(UNSUPPORTED, ERROR, source, violation.message(),
                        context.lineWithin(document.getKey(), "result:", token(violation)), null));
            }
        }
        for (JobFile job : manifest.jobs()) {
            String source = LintSupport.relative(appHome, job.source());
            // A job binds its input: through the route's binder (decision 27), so its kinds
            // are judged by the route's predicate.
            for (DeclaredKinds.Violation violation : DeclaredKinds.inputViolations(
                    job.definition())) {
                findings.add(new LintFinding(UNSUPPORTED, ERROR, source, violation.message(),
                        context.lineWithin(job.source(), "input:", token(violation)), null));
            }
            for (PipelineStep step : job.definition().pipeline()) {
                if (step.chunk() == null) {
                    continue;
                }
                chunkBinding(job, step, "reader", step.chunk().reader(), source, findings);
                chunkBinding(job, step, "writer", step.chunk().writer(), source, findings);
            }
        }
    }

    /** The last key of a violation's path, as the token its line is found by. */
    private static String token(DeclaredKinds.Violation violation) {
        String key = violation.key();
        return key.substring(key.lastIndexOf('.') + 1) + ":";
    }

    private static void chunkBinding(JobFile job, PipelineStep step, String role,
            Binding binding, String source, List<LintFinding> findings) {
        if (binding == null || binding.result().isEmpty()) {
            return;
        }
        findings.add(new LintFinding(UNSUPPORTED, ERROR, source, "Job '"
                + job.definition().id() + "' step '" + step.id() + "': chunk." + role
                + ".result: is not applied - a chunk " + role + " reads each column in the kind"
                + " the database declares, and a result: declaration is a route source's or a"
                + " command step's"));
    }
}
