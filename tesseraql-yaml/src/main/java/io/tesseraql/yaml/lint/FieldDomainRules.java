package io.tesseraql.yaml.lint;

import static io.tesseraql.yaml.lint.LintFinding.Severity.WARNING;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field domains and the declarations that {@code domain:} loosens.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class FieldDomainRules implements LintRule {

    private static final String DOMAIN_LOOSENED = "TQL-FIELD-4610";

    private static final String UNREFERENCED_DOMAIN = "TQL-FIELD-4611";

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        lintFieldDomains(context.appHome(), manifest, findings);
    }

    /**
     * Lints field domains (docs/field-domains.md): a route override that loosens a domain
     * constraint is exactly the drift domains exist to prevent, and a domain nothing references
     * is either dead or a missed reference. Duplicate names, unknown references, and operational
     * keys inside a domain already failed the manifest load (TQL-FIELD-4600..4603).
     */
    void lintFieldDomains(Path appHome, AppManifest manifest,
            List<LintFinding> findings) {
        io.tesseraql.yaml.domain.FieldDomains domains = io.tesseraql.yaml.domain.FieldDomains
                .load(appHome);
        if (domains.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            String source = appHome.relativize(document.getKey()).toString();
            lintInputs(document.getValue().input(), domains, source, referenced, findings);
            // A result: entry reads a domain too (docs/temporal-semantics.md T3) - the
            // date a legacy column stores as text is the business field the request binds.
            // It restates at most format:, which is neither tightening nor loosening: the
            // API takes ISO and the column holds 20240103, so the restatement is not a finding.
            // The constraint keys are not applied on read, so there is nothing to loosen.
            java.util.stream.Stream.concat(document.getValue().sources().values().stream(),
                    document.getValue().steps().values().stream())
                    .flatMap(binding -> binding.result().values().stream())
                    .map(InputField::domain)
                    .filter(java.util.Objects::nonNull)
                    .forEach(referenced::add);
            // A file column reads a domain too (docs/temporal-semantics.md decision 25): its
            // type: and format:, the two keys a column and a field share.
            java.util.stream.Stream.of(document.getValue().fileImport() == null
                    ? List.<io.tesseraql.yaml.model.ColumnSpec>of()
                    : document.getValue().fileImport().columns(),
                    document.getValue().fileExport() == null
                            ? List.<io.tesseraql.yaml.model.ColumnSpec>of()
                            : document.getValue().fileExport().columns())
                    .flatMap(List::stream)
                    .map(io.tesseraql.yaml.model.ColumnSpec::domain)
                    .filter(java.util.Objects::nonNull)
                    .forEach(referenced::add);
        }
        // A job's declarations reference and loosen a domain exactly as a route's do
        // (docs/temporal-semantics.md decision 27): its input:, each export step's columns,
        // a poll job's import columns.
        for (io.tesseraql.yaml.manifest.JobFile job : manifest.jobs()) {
            String source = LintSupport.relative(appHome, job.source());
            lintInputs(job.definition().input(), domains, source, referenced, findings);
            for (io.tesseraql.yaml.model.PipelineStep step : job.definition().pipeline()) {
                if (step.export() != null) {
                    step.export().columns().stream()
                            .map(io.tesseraql.yaml.model.ColumnSpec::domain)
                            .filter(java.util.Objects::nonNull)
                            .forEach(referenced::add);
                }
            }
            if (job.definition().fileImport() != null) {
                job.definition().fileImport().columns().stream()
                        .map(io.tesseraql.yaml.model.ColumnSpec::domain)
                        .filter(java.util.Objects::nonNull)
                        .forEach(referenced::add);
            }
        }
        domains.domains().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding(UNREFERENCED_DOMAIN, WARNING,
                        "domains",
                        "Domain '" + name + "' is declared but never referenced")));
    }

    /** One document's {@code input:} block: the references it makes and the domains it loosens. */
    private static void lintInputs(Map<String, InputField> input,
            io.tesseraql.yaml.domain.FieldDomains domains, String source,
            Set<String> referenced, List<LintFinding> findings) {
        input.forEach((name, field) -> {
            if (field.domain() == null) {
                return;
            }
            referenced.add(field.domain());
            InputField domain = domains.domains().get(field.domain());
            if (domain == null) {
                return;
            }
            loosened(field, domain).forEach(what -> findings.add(new LintFinding(
                    DOMAIN_LOOSENED, WARNING, source,
                    "Field '" + name + "' loosens domain '" + field.domain() + "': " + what
                            + " — a loosened copy is the drift domains exist to prevent")));
        });
    }

    /** The ways the merged field is looser than its domain, as human-readable clauses. */
    private static List<String> loosened(InputField merged, InputField domain) {
        List<String> ways = new ArrayList<>();
        if (domain.maxLength() != null && merged.maxLength() != null
                && merged.maxLength() > domain.maxLength()) {
            ways.add("maxLength " + merged.maxLength() + " > " + domain.maxLength());
        }
        if (domain.minLength() != null && merged.minLength() != null
                && merged.minLength() < domain.minLength()) {
            ways.add("minLength " + merged.minLength() + " < " + domain.minLength());
        }
        if (domain.min() != null && merged.min() != null
                && merged.min().compareTo(domain.min()) < 0) {
            ways.add("min " + merged.min() + " < " + domain.min());
        }
        if (domain.max() != null && merged.max() != null
                && merged.max().compareTo(domain.max()) > 0) {
            ways.add("max " + merged.max() + " > " + domain.max());
        }
        if (domain.enumValues() != null && merged.enumValues() != null
                && !domain.enumValues().containsAll(merged.enumValues())) {
            ways.add("enum adds values outside the domain's set");
        }
        return ways;
    }
}
