package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Renders a parsed 2-way SQL template against parameters into an executable {@link BoundSql}
 * (design ch. 8.1, 8.2). Directive comments are stripped, bind sites become {@code ?}
 * placeholders, and a source map, coverage trace, and variant identity are collected.
 */
public final class SqlRenderer {

    private static final TqlErrorCode MISSING_LIST = new TqlErrorCode(TqlDomain.SQL, 2001);

    private final StringBuilder out = new StringBuilder();
    private final List<BoundParameter> parameters = new ArrayList<>();
    private final SourceMap sourceMap = new SourceMap();
    private final CoverageTrace coverage = new CoverageTrace();
    private final Map<String, Object> scope;
    private final EvaluationContext context;

    private SqlRenderer(Map<String, Object> params) {
        this.scope = new HashMap<>(params);
        this.context = new EvaluationContext(scope);
    }

    /** Parses and renders a 2-way SQL template against the given parameters. */
    public static BoundSql render(String source, Map<String, Object> params) {
        return render(Sql2WayParser.parse(source), params);
    }

    /** Renders an already-parsed node tree against the given parameters. */
    public static BoundSql render(List<SqlNode> nodes, Map<String, Object> params) {
        SqlRenderer renderer = new SqlRenderer(params);
        renderer.renderNodes(nodes);
        return new BoundSql(
                renderer.out.toString(),
                renderer.parameters,
                renderer.sourceMap,
                renderer.coverage,
                SqlVariant.of(renderer.coverage.branches()));
    }

    private void renderNodes(List<SqlNode> nodes) {
        for (SqlNode node : nodes) {
            switch (node) {
                case SqlNode.Text text -> appendText(text.text(), text.startLine());
                case SqlNode.Bind bind -> appendBind(bind);
                case SqlNode.ListBind listBind -> appendListBind(listBind);
                case SqlNode.If ifNode -> renderIf(ifNode);
                case SqlNode.For forNode -> renderFor(forNode);
            }
        }
    }

    private void renderIf(SqlNode.If ifNode) {
        boolean done = false;
        for (SqlNode.If.Branch branch : ifNode.branches()) {
            if (done) {
                break;
            }
            if (branch.condition() == null) {
                coverage.recordBranch(branch.sourceLine(), true);
                renderNodes(branch.body());
                done = true;
            } else {
                boolean taken = branch.condition().evalBoolean(context);
                coverage.recordBranch(branch.sourceLine(), taken);
                if (taken) {
                    renderNodes(branch.body());
                    done = true;
                }
            }
        }
    }

    private void renderFor(SqlNode.For forNode) {
        Object value = forNode.listExpression().eval(context);
        List<Object> elements = toList(value, forNode.listExpressionSource(), forNode.sourceLine());
        Object previous = scope.get(forNode.itemVar());
        boolean hadPrevious = scope.containsKey(forNode.itemVar());
        try {
            for (Object element : elements) {
                scope.put(forNode.itemVar(), element);
                renderNodes(forNode.body());
            }
        } finally {
            if (hadPrevious) {
                scope.put(forNode.itemVar(), previous);
            } else {
                scope.remove(forNode.itemVar());
            }
        }
    }

    private void appendBind(SqlNode.Bind bind) {
        Object value = bind.expression().eval(context);
        mapToSource("?", bind.sourceLine());
        parameters.add(new BoundParameter(bind.expressionSource(), value, bind.sourceLine()));
        coverage.coverLine(bind.sourceLine());
    }

    private void appendListBind(SqlNode.ListBind listBind) {
        Object value = listBind.expression().eval(context);
        List<Object> elements = toList(value, listBind.expressionSource(), listBind.sourceLine());
        coverage.coverLine(listBind.sourceLine());
        if (elements.isEmpty()) {
            // An empty IN list is invalid SQL; (null) yields a predicate that matches no rows.
            mapToSource("(null)", listBind.sourceLine());
            return;
        }
        int start = out.length();
        out.append('(');
        for (int i = 0; i < elements.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append('?');
            parameters.add(new BoundParameter(
                    listBind.expressionSource() + "[" + i + "]", elements.get(i), listBind.sourceLine()));
        }
        out.append(')');
        sourceMap.addSegment(start, out.length(), listBind.sourceLine());
    }

    private void mapToSource(String literal, int sourceLine) {
        int start = out.length();
        out.append(literal);
        sourceMap.addSegment(start, out.length(), sourceLine);
    }

    private void appendText(String text, int startLine) {
        int currentLine = startLine;
        int segmentStart = out.length();
        boolean nonWhitespace = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c);
            if (!Character.isWhitespace(c)) {
                nonWhitespace = true;
            }
            if (c == '\n') {
                sourceMap.addSegment(segmentStart, out.length(), currentLine);
                if (nonWhitespace) {
                    coverage.coverLine(currentLine);
                }
                currentLine++;
                segmentStart = out.length();
                nonWhitespace = false;
            }
        }
        if (out.length() > segmentStart) {
            sourceMap.addSegment(segmentStart, out.length(), currentLine);
            if (nonWhitespace) {
                coverage.coverLine(currentLine);
            }
        }
    }

    private List<Object> toList(Object value, String expression, int sourceLine) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            iterable.forEach(result::add);
            return result;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            List<Object> result = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                result.add(Array.get(value, i));
            }
            return result;
        }
        throw TqlException.builder(MISSING_LIST)
                .message("Expected a collection for '" + expression + "' but got "
                        + Objects.requireNonNull(value).getClass().getSimpleName())
                .line(sourceLine)
                .build();
    }
}
