package io.tesseraql.yaml.lint;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared validation rule sets ({@code rules/}).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class RuleSetRules implements LintRule {

    /** The run's memoized IO and cross-rule state, set at the top of {@link #lint}. */
    private LintContext context;

    @Override
    public void lint(LintContext context, AppManifest manifest,
            List<LintFinding> findings) {
        this.context = context;
        lintRuleSets(context.appHome(), manifest, findings);
    }

    /**
     * Lints shared validation rules (docs/validation-rule-sets.md): a rule nothing references
     * is either dead or a missed reference, and a route-local rule that says the same thing as
     * a shared one is the copy-paste rule sets exist to replace. Unknown references, bind
     * contracts on both sides, and duplicates already failed the load (TQL-FIELD-4604..4609).
     */
    void lintRuleSets(Path appHome, AppManifest manifest, List<LintFinding> findings) {
        io.tesseraql.yaml.rules.ValidationRuleSets sets = io.tesseraql.yaml.rules.ValidationRuleSets
                .load(appHome, new io.tesseraql.yaml.SimpleYamlParser());
        if (sets.isEmpty()) {
            return;
        }
        Set<String> referenced = new HashSet<>();
        for (Map.Entry<Path, RouteDefinition> document : LintSupport.authoringDocuments(manifest)) {
            String source = appHome.relativize(document.getKey()).toString();
            document.getValue().validate().forEach((id, rule) -> {
                if (rule.use() != null) {
                    referenced.add(rule.use());
                    return;
                }
                duplicateOf(rule, sets).ifPresent(shared -> findings.add(new LintFinding(
                        "TQL-FIELD-4613", "warning", source,
                        "Validation rule '" + id + "' repeats shared rule '" + shared
                                + "' — reference it with use: so the two cannot drift apart")));
            });
        }
        sets.rules().keySet().stream()
                .filter(name -> !referenced.contains(name))
                .forEach(name -> findings.add(new LintFinding("TQL-FIELD-4612", "warning",
                        "rules", "Rule '" + name + "' is declared but never referenced")));
    }

    /**
     * The shared rule a route-local one restates, if any. Only the rule's own substance counts —
     * expression text, or SQL file contents — because {@code field:}, {@code when:} and the
     * message are the reference's local wiring and differ legitimately between two uses of the
     * same rule.
     */
    private static java.util.Optional<String> duplicateOf(
            io.tesseraql.yaml.model.ValidationRule local,
            io.tesseraql.yaml.rules.ValidationRuleSets sets) {
        if (local.rule() == null || local.rule().isBlank()) {
            // Two SQL rules are the same rule when they name the same file, which the shared
            // declaration already expresses; comparing file *contents* would flag a route that
            // legitimately keeps its own copy of similar SQL.
            return java.util.Optional.empty();
        }
        String expression = local.rule().trim();
        return sets.rules().entrySet().stream()
                .filter(entry -> entry.getValue().rule() != null
                        && entry.getValue().rule().trim().equals(expression))
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
