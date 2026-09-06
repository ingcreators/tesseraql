package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers more than one rule family needs: the two positioning/formatting
 * primitives every family calls, the app's authoring documents, and the two
 * expression walks that the decision and workflow families share.
 *
 * <p>Extracted verbatim from {@code AppLinter} (docs/lint-restructure.md decision 1).
 */
final class LintSupport {

    private LintSupport() {
    }

    /**
     * One SQL file a document declares: where it hangs, the resolved path, and the {@code params:}
     * map that binds into it.
     *
     * @param slot   a dotted label naming where it hangs, for a finding's message
     * @param file   the path, resolved against the document's directory and not checked to exist
     * @param params bind name to source expression, never null
     */
    record DocumentSql(String slot, Path file, Map<String, String> params) {
    }

    /**
     * Every SQL file a route, consumer or tool document declares — the complete set, in authored
     * order.
     *
     * <p>It exists because the lint package had seven partial, mutually inconsistent enumerations
     * of this, and the widest of them still missed two slots. The narrowest was the
     * <em>injection</em> lint, which read {@code definition.main()} and returned — so the same
     * embedded variable was an error under {@code sources: main:} and clean under a named source,
     * a step or a validation rule (docs/two-way-sql-parser.md decision 15).
     *
     * <p>An {@code enrich:} block hangs off a binding, so it is reachable from both
     * {@code sources:} and {@code steps:}. A contract or service binding carries no file and does
     * not appear. Nothing else is filtered: a caller that wants only files that exist, or only
     * query-mode bindings, decides that for itself.
     */
    static List<DocumentSql> documentSql(Path documentSource, RouteDefinition definition) {
        Path dir = documentSource.getParent();
        List<DocumentSql> slots = new ArrayList<>();
        definition.sources().forEach((name, binding) -> addBinding(slots, dir,
                "sources." + name, binding));
        definition.steps().forEach((name, binding) -> addBinding(slots, dir,
                "steps." + name, binding));
        definition.validate().forEach((name, rule) -> {
            if (rule.file() != null) {
                slots.add(new DocumentSql("validate." + name, dir.resolve(rule.file()).normalize(),
                        rule.params()));
            }
        });
        if (definition.fileExport() != null && definition.fileExport().after() != null
                && definition.fileExport().after().sql() != null
                && definition.fileExport().after().sql().file() != null) {
            Binding.SqlArm after = definition.fileExport().after().sql();
            slots.add(new DocumentSql("export.after", dir.resolve(after.file()).normalize(),
                    after.params() == null ? Map.of() : after.params()));
        }
        return slots;
    }

    /** A binding's own SQL file, then every enrichment reference hanging off it. */
    private static void addBinding(List<DocumentSql> slots, Path dir, String slot,
            Binding binding) {
        if (binding.file() != null) {
            slots.add(new DocumentSql(slot, dir.resolve(binding.file()).normalize(),
                    binding.params() == null ? Map.of() : binding.params()));
        }
        if (binding.enrich() == null) {
            return;
        }
        binding.enrich().forEach((name, enrich) -> {
            if (enrich.sql() != null && enrich.sql().file() != null) {
                slots.add(new DocumentSql(slot + ".enrich." + name,
                        dir.resolve(enrich.sql().file()).normalize(),
                        enrich.sql().params() == null ? Map.of() : enrich.sql().params()));
            }
        });
    }

    /**
     * The distinct bind expressions matching {@code matches} across a document's parseable SQL
     * files. Unparseable SQL is its own lint's concern and contributes nothing here.
     */
    static Set<String> ambientBinds(LintContext context, Path source, RouteDefinition def,
            java.util.function.Predicate<String> matches) {
        Set<String> found = new LinkedHashSet<>();
        for (DocumentSql slot : documentSql(source, def)) {
            Path sqlFile = slot.file();
            if (!Files.isRegularFile(sqlFile)) {
                continue;
            }
            List<SqlNode> nodes = context.sqlNodes(sqlFile);
            if (nodes == null) {
                continue;
            }
            SqlNode.walk(nodes, node -> {
                String expressionSource = switch (node) {
                    case SqlNode.Bind bind -> bind.expressionSource();
                    case SqlNode.ListBind bind -> bind.expressionSource();
                    default -> null;
                };
                if (expressionSource != null) {
                    String expression = expressionSource.trim();
                    if (matches.test(expression)) {
                        found.add(expression);
                    }
                }
            });
        }
        return found;
    }

    /**
     * Every document that can declare {@code input:} or {@code validate:} — web routes, queue
     * consumers, and MCP tools. Any check that answers "is this shared definition referenced?"
     * has to see all three, or resolving them everywhere just moves the bug: a domain used only
     * by a tool would be reported as unreferenced.
     */
    static List<Map.Entry<Path, RouteDefinition>> authoringDocuments(
            AppManifest manifest) {
        List<Map.Entry<Path, RouteDefinition>> documents = new ArrayList<>();
        manifest.routes().forEach(r -> documents.add(Map.entry(r.source(), r.definition())));
        manifest.consumers().forEach(c -> documents.add(Map.entry(c.source(), c.definition())));
        manifest.tools().forEach(t -> documents.add(Map.entry(t.source(), t.definition())));
        return documents;
    }

    static void collectGuardPaths(Expr expr, List<List<String>> out) {
        if (expr instanceof Expr.Path p) {
            out.add(p.segments());
        } else if (expr instanceof Expr.Not n) {
            collectGuardPaths(n.operand(), out);
        } else if (expr instanceof Expr.Logical l) {
            collectGuardPaths(l.left(), out);
            collectGuardPaths(l.right(), out);
        } else if (expr instanceof Expr.Comparison c) {
            collectGuardPaths(c.left(), out);
            collectGuardPaths(c.right(), out);
        }
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String relative(Path appHome, Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /**
     * The {@code *.yml} documents of one app directory, in the order its loader reads them —
     * for the families whose loader hands back a merged namespace rather than the files it built
     * it from, and which therefore need the file itself to lint its keys.
     */
    static List<Path> documents(Path appHome, String directory) {
        Path dir = appHome.resolve(directory);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (java.io.IOException unreadable) {
            // An unreadable directory is the loader's problem to report; the lint stays quiet.
            return List.of();
        }
    }
}
