package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.ERROR;

import io.tesseraql.yaml.app.DeclaredKinds;
import io.tesseraql.yaml.app.ExportDeclarations;
import io.tesseraql.yaml.app.InputDefaults;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The {@code type:} a field declares, judged where it is declared (docs/temporal-semantics.md
 * T3): an {@code input:} whose type no request binds, a {@code result:} entry whose kind no
 * read parses or whose {@code format:} its parser refuses, a {@code result:} on a binding
 * that publishes no rows or on an export recipe — all {@code TQL-YAML-1064}, reported from
 * the predicate the compiler refuses from — and a {@code result:} on a job's chunk reader or
 * writer, which the typed batch readers never apply (they keep the kind the read seam gives
 * them), the same code from the predicate the job registration refuses from — and a job's
 * {@code input:}, which binds through the route's binder, judged by the route's predicate. An input's {@code default:} is judged here too, by the input's own rules
 * ({@code TQL-YAML-1072}, docs/audit-low-leads.md XD-07b): the literal the binder hands every
 * request that omits the field was judged by nobody.
 */
final class DeclaredKindRules implements LintRule {

    private static final String UNSUPPORTED = DeclaredKinds.UNSUPPORTED.toString();

    @Override
    public void lint(LintContext context, AppManifest manifest, List<LintFinding> findings) {
        Path appHome = context.appHome();
        String appName = ExportRules.appName(manifest.config());
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            String source = LintSupport.relative(appHome, document.getKey());
            RouteDefinition route = document.getValue();
            for (DeclaredKinds.Violation violation : DeclaredKinds.inputViolations(route)) {
                findings.add(new LintFinding(UNSUPPORTED, ERROR, source, violation.message(),
                        context.lineWithin(document.getKey(), "input:", token(violation)), null));
            }
            defaults(context, document.getKey(), source, InputDefaults.violations(appName,
                    "route '" + ExportDeclarations.bounded(route.id()) + "'", route.input()),
                    findings);
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
            defaults(context, job.source(), source, InputDefaults.violations(appName,
                    "job '" + ExportDeclarations.bounded(job.definition().id()) + "'",
                    job.definition().input()), findings);
            for (DeclaredKinds.Violation violation : DeclaredKinds.chunkViolations(
                    job.definition())) {
                findings.add(new LintFinding(UNSUPPORTED, ERROR, source, violation.message(),
                        context.lineWithin(job.source(), "chunk:", "result:"), null));
            }
        }
    }

    /** Each refused default as a finding, at the {@code default:} line inside {@code input:}. */
    private static void defaults(LintContext context, Path document, String source,
            List<ExportDeclarations.Violation> violations, List<LintFinding> findings) {
        for (ExportDeclarations.Violation violation : violations) {
            findings.add(new LintFinding(violation.code().toString(), ERROR, source,
                    violation.message(),
                    context.lineWithin(document, "input:", "default:"), null));
        }
    }

    /** The last key of a violation's path, as the token its line is found by. */
    private static String token(DeclaredKinds.Violation violation) {
        String key = violation.key();
        return key.substring(key.lastIndexOf('.') + 1) + ":";
    }
}
