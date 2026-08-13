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

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
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
            document.getValue().input().forEach((name, field) -> {
                if (field.domain() == null) {
                    return;
                }
                referenced.add(field.domain());
                InputField domain = domains.domains().get(field.domain());
                if (domain == null) {
                    return;
                }
                loosened(name, field, domain).forEach(what -> findings.add(new LintFinding(
                        DOMAIN_LOOSENED, WARNING, source,
                        "Field '" + name + "' loosens domain '" + field.domain() + "': " + what
                                + " — a loosened copy is the drift domains exist to prevent")));
            });
        }
        domains.domains().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding(UNREFERENCED_DOMAIN, WARNING,
                        "domains",
                        "Domain '" + name + "' is declared but never referenced")));
    }

    /** The ways the merged field is looser than its domain, as human-readable clauses. */
    private static List<String> loosened(String name, InputField merged, InputField domain) {
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
