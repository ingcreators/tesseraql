package io.tesseraql.yaml.lint;

import io.tesseraql.core.expr.Expr;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.yaml.manifest.AppManifest;
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
     * The distinct bind expressions matching {@code matches} across a document's parseable SQL
     * files — its steps, named sources, and validation rules. Unparseable SQL is its own lint's
     * concern and contributes nothing here.
     */
    static Set<String> ambientBinds(LintContext context, Path source, RouteDefinition def,
            java.util.function.Predicate<String> matches) {
        Set<String> found = new LinkedHashSet<>();
        Path dir = source.getParent();
        List<String> files = new ArrayList<>();
        def.steps().values().forEach(step -> {
            if (step.file() != null) {
                files.add(step.file());
            }
        });
        def.sources().values().forEach(query -> {
            if (query.file() != null) {
                files.add(query.file());
            }
        });
        def.validate().values().forEach(rule -> {
            if (rule.file() != null) {
                files.add(rule.file());
            }
        });
        for (String file : files) {
            Path sqlFile = dir.resolve(file).normalize();
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
}
