package io.tesseraql.yaml.lint;

import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Route-level {@code enrich:} entries and the keyed-reference contract they
 * bind (docs/lookups.md).
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class EnrichRules {

    private EnrichRules() {
    }

    /**
     * Statically checks every source's {@code enrich:} entries (docs/lookups.md): the reference
     * must be exactly one mechanism ({@code TQL-YAML-1046}), the join and the composition must
     * be sayable ({@code TQL-YAML-1047}), and the reference SQL must actually bind the key set
     * ({@code TQL-YAML-1048}) — a reference query that never mentions {@code keys} reads the
     * whole table once per batch and still returns the right answer, which is why only the build
     * can catch it.
     *
     * <p>The {@code into:} placement lint retired with {@code into:} itself: an enrichment nests
     * under the source it transforms (docs/unified-sources.md decision 5), so there is no
     * back-reference left to point at a result the document does not publish. Its code is not
     * named here — the error index scans these sources for literal codes, and a retired one
     * mentioned in prose is republished as a live code with no meaning.
     */
    static void lintEnrich(LintContext context, AppConfig config, Path file,
            RouteDefinition definition,
            String source, List<LintFinding> findings) {
        definition.sources().forEach((sourceName, binding) -> binding.enrich()
                .forEach((name, spec) -> lintEnrichEntry(context, config, file, definition,
                        sourceName,
                        name, spec, source, findings)));
    }

    static void lintEnrichEntry(LintContext context, AppConfig config, Path file,
            RouteDefinition definition,
            String sourceName, String name,
            io.tesseraql.yaml.model.EnrichSpec spec, String source, List<LintFinding> findings) {
        {
            // A composition names a sibling the document actually declares — and never itself,
            // which would be a source composing its own rows into themselves.
            if (spec.composesSource()
                    && (!definition.sources().containsKey(spec.source())
                            || spec.source().equals(sourceName))) {
                findings.add(new LintFinding("TQL-YAML-1046", "error", source,
                        "enrich '" + name + "': source: '" + spec.source()
                                + "' is not another source of this document",
                        context.lineOf(file, "enrich:"), null));
                return;
            }
            boolean hasSql = spec.sql() != null && spec.sql().file() != null
                    && !spec.sql().file().isBlank();
            boolean hasHttp = spec.http() != null && spec.http().url() != null
                    && !spec.http().url().isBlank();
            int arms = (hasSql ? 1 : 0) + (hasHttp ? 1 : 0) + (spec.composesSource() ? 1 : 0);
            if (arms != 1) {
                findings.add(new LintFinding("TQL-YAML-1046", "error", source,
                        "enrich '" + name + "': needs exactly one reference — sql: with a"
                                + " file:, http: with a url:, or source: naming a sibling",
                        context.lineOf(file, "enrich:"), null));
                return;
            }
            boolean attaches = spec.as() != null && !spec.as().isBlank();
            boolean merges = spec.merges()
                    && spec.merge().stream().noneMatch(c -> c == null || c.isBlank());
            if (spec.on().isEmpty() || attaches == merges) {
                findings.add(new LintFinding("TQL-YAML-1047", "error", source,
                        "enrich '" + name + "': needs a non-empty on: parentColumn: childColumn"
                                + " map and exactly one of as: (attach a list) or merge: (copy"
                                + " columns onto each row)",
                        context.lineOf(file, "enrich:"), null));
            }
            if (spec.composesSource()) {
                // Nothing is fetched, so there is no key set to bind and no call to check.
                return;
            }
            if (hasSql) {
                Path sqlFile = file.getParent().resolve(spec.sql().file()).normalize();
                if (Files.isRegularFile(sqlFile) && !bindsKeys(context, sqlFile)) {
                    findings.add(new LintFinding("TQL-YAML-1048", "error", source,
                            "enrich '" + name + "': " + spec.sql().file() + " never binds"
                                    + " 'keys' — the reference would be read whole once per"
                                    + " batch instead of by the keys being looked up",
                            context.lineOf(file, "enrich:"), null));
                }
            } else if (!usesKeys(spec)) {
                // The HTTP twin of the same defect: a call that mentions neither the key set
                // nor the key sends the identical request every time and answers plausibly.
                findings.add(new LintFinding("TQL-YAML-1048", "error", source,
                        "enrich '" + name + "': the " + spec.effectiveMode() + " reference never"
                                + " uses " + (spec.batches() ? "'keys'" : "'key.<column>'")
                                + " — every request would be identical",
                        context.lineOf(file, "enrich:"), null));
            }
            if (hasHttp) {
                HttpSourceRules.lintHttpCall(config, "enrich '" + name + "'", spec.http().call(),
                        source,
                        findings);
            }
        }
    }

    /**
     * Whether an HTTP reference actually varies with the keys: a batch call must carry the key
     * set ({@code keys}), a per-row call one key ({@code key.<column>}). The url, the query
     * bindings, the body and the headers are all places it can appear.
     */
    static boolean usesKeys(io.tesseraql.yaml.model.EnrichSpec spec) {
        String needle = spec.batches() ? "keys" : "key.";
        io.tesseraql.yaml.model.HttpCallSpec call = spec.http().call();
        java.util.List<String> declarations = new ArrayList<>();
        declarations.add(call.url() == null ? "" : call.url());
        declarations.add(call.body() == null ? "" : call.body());
        declarations.addAll(call.query().values());
        declarations.addAll(call.headers().values());
        return declarations.stream().anyMatch(declared -> declared.contains(needle));
    }

    /** Whether a reference query mentions the {@code keys} bind, as a value list or a loop. */
    static boolean bindsKeys(LintContext context, Path sqlFile) {
        List<SqlNode> nodes = context.sqlNodes(sqlFile);
        if (nodes == null) {
            // Unparseable SQL is its own lint's concern; do not double-report it here.
            return true;
        }
        // The walk visits a loop's body itself, so the For case only checks the list source.
        boolean[] found = {false};
        SqlNode.walk(nodes, node -> found[0] = found[0] || switch (node) {
            case SqlNode.Bind bind -> isKeys(bind.expressionSource());
            case SqlNode.ListBind bind -> isKeys(bind.expressionSource());
            case SqlNode.For loop -> isKeys(loop.listExpressionSource());
            default -> false;
        });
        return found[0];
    }

    /** {@code keys} itself, or a path rooted at it. */
    static boolean isKeys(String expressionSource) {
        String expression = expressionSource == null ? "" : expressionSource.trim();
        return expression.equals("keys") || expression.startsWith("keys.");
    }
}
