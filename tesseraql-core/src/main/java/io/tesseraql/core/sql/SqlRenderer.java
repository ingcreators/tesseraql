package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a parsed 2-way SQL template against parameters into an executable {@link BoundSql}
 * (design ch. 8.1, 8.2). Directive comments are stripped, bind sites become {@code ?}
 * placeholders, and a source map, coverage trace, and variant identity are collected.
 */
public final class SqlRenderer {

    private static final TqlErrorCode MISSING_LIST = new TqlErrorCode(TqlDomain.SQL, 2001);
    /** TQL-SQL-2108: an embedded variable resolved to a value carrying SQL meta-characters. */
    private static final TqlErrorCode UNSAFE_EMBEDDED = new TqlErrorCode(TqlDomain.SQL, 2108);
    /** A {@code {placeholder}} reference inside an embedded-variable template. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}]+)}");

    private final StringBuilder out = new StringBuilder();
    private final List<BoundParameter> parameters = new ArrayList<>();
    private final SourceMap sourceMap = new SourceMap();
    private final CoverageTrace coverage = new CoverageTrace();
    private final Map<String, Object> scope;
    private final EvaluationContext context;
    private final ScopeResolver scopeResolver;
    private final Map<String, Object> scopeContext;
    private final FilePathResolver filePathResolver;

    private SqlRenderer(Map<String, Object> params, ScopeResolver scopeResolver,
            Map<String, Object> scopeContext, FilePathResolver filePathResolver) {
        this.scope = new HashMap<>(params);
        this.context = new EvaluationContext(scope);
        this.scopeResolver = scopeResolver;
        this.scopeContext = scopeContext;
        this.filePathResolver = filePathResolver;
    }

    /** Parses and renders a 2-way SQL template against the given parameters. */
    public static BoundSql render(String source, Map<String, Object> params) {
        return render(Sql2WayParser.parse(source), params);
    }

    /** Renders an already-parsed node tree against the given parameters. */
    public static BoundSql render(List<SqlNode> nodes, Map<String, Object> params) {
        return render(nodes, params, ScopeResolver.UNSUPPORTED, Map.of());
    }

    /**
     * Renders a node tree, expanding any {@code /*%scope%/} directive through {@code scopeResolver}
     * against {@code scopeContext} (roadmap Phase 29). The render paths that never carry a scope
     * directive use the two-argument overload, whose default resolver rejects one outright.
     */
    public static BoundSql render(List<SqlNode> nodes, Map<String, Object> params,
            ScopeResolver scopeResolver, Map<String, Object> scopeContext) {
        return render(nodes, params, scopeResolver, scopeContext, FilePathResolver.UNSUPPORTED);
    }

    /**
     * Renders a node tree that may additionally carry {@code ${scope.*}}/{@code ${dataset.*}} file
     * placeholders, resolved through {@code filePathResolver} (docs/duckdb.md). Only the analytics
     * execution path passes a real resolver; everywhere else a file placeholder fails loudly.
     */
    public static BoundSql render(List<SqlNode> nodes, Map<String, Object> params,
            ScopeResolver scopeResolver, Map<String, Object> scopeContext,
            FilePathResolver filePathResolver) {
        SqlRenderer renderer = new SqlRenderer(params, scopeResolver, scopeContext,
                filePathResolver);
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
                case SqlNode.Embedded embedded -> appendEmbedded(embedded);
                case SqlNode.If ifNode -> renderIf(ifNode);
                case SqlNode.For forNode -> renderFor(forNode);
                case SqlNode.Scope scopeNode -> renderScope(scopeNode);
                case SqlNode.FilePath filePath -> appendFilePath(filePath);
            }
        }
    }

    /**
     * Emits a file placeholder as an ordinary {@code ?} bound to the resolver's absolute path —
     * never interpolated text — so path values ride the same parameter channel as every other bind.
     */
    private void appendFilePath(SqlNode.FilePath filePath) {
        // The scope channel passes the declared scope name; the dataset channel evaluates the
        // named parameter here (against the same bind map every ordinary bind uses) and passes
        // the caller-supplied reference — the resolver authorizes it before any path exists.
        String reference = filePath.name();
        if ("dataset".equals(filePath.channel())) {
            Object value = context.resolve(List.of(filePath.name()));
            reference = value == null ? null : String.valueOf(value);
        }
        String resolved = filePathResolver.resolve(filePath.channel(), reference,
                filePath.suffix(), scopeContext);
        mapToSource("?", filePath.sourceLine());
        parameters.add(new BoundParameter(
                "${" + filePath.channel() + "." + filePath.name() + "}" + filePath.suffix(),
                resolved, filePath.sourceLine()));
        coverage.coverLine(filePath.sourceLine());
    }

    /**
     * Expands a {@code /*%scope%/} directive (roadmap Phase 29): the resolver decides the predicate
     * sub-template and its bind values from the principal; we render that sub-template inline, with
     * its bindings layered onto the scope so the fragment's own {@code /* expr *}{@code /} binds
     * resolve (and restored afterwards so they cannot leak into the rest of the statement).
     */
    private void renderScope(SqlNode.Scope node) {
        ScopeResolver.Resolved resolved = scopeResolver.resolve(node.name(), node.alias(),
                scopeContext);
        Map<String, Object> saved = new HashMap<>();
        java.util.Set<String> added = new java.util.HashSet<>();
        for (Map.Entry<String, Object> binding : resolved.bindings().entrySet()) {
            if (scope.containsKey(binding.getKey())) {
                saved.put(binding.getKey(), scope.get(binding.getKey()));
            } else {
                added.add(binding.getKey());
            }
            scope.put(binding.getKey(), binding.getValue());
        }
        try {
            // `as boolean` makes the scope a SELECT-list flag (1/0), portable across dialects that
            // lack a boolean type (SQL Server); otherwise it is a WHERE predicate rendered as-is.
            if (node.asBoolean()) {
                mapToSource("case when ", node.sourceLine());
            }
            renderNodes(resolved.nodes());
            if (node.asBoolean()) {
                mapToSource(" then 1 else 0 end", node.sourceLine());
            }
        } finally {
            saved.forEach(scope::put);
            added.forEach(scope::remove);
        }
        coverage.coverLine(node.sourceLine());
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
        String indexVar = forNode.itemVar() + "_index";
        Object previous = scope.get(forNode.itemVar());
        boolean hadPrevious = scope.containsKey(forNode.itemVar());
        Object previousIndex = scope.get(indexVar);
        boolean hadPreviousIndex = scope.containsKey(indexVar);
        try {
            for (int i = 0; i < elements.size(); i++) {
                if (i > 0 && forNode.separator() != null) {
                    mapToSource(forNode.separator(), forNode.sourceLine());
                }
                scope.put(forNode.itemVar(), elements.get(i));
                scope.put(indexVar, i);
                renderNodes(forNode.body());
            }
        } finally {
            restore(forNode.itemVar(), previous, hadPrevious);
            restore(indexVar, previousIndex, hadPreviousIndex);
        }
    }

    private void restore(String name, Object previous, boolean hadPrevious) {
        if (hadPrevious) {
            scope.put(name, previous);
        } else {
            scope.remove(name);
        }
    }

    /**
     * Emits an embedded variable: each {@code {placeholder}} in the template is resolved against the
     * parameters and the result is appended to the SQL <em>text</em> (no {@code ?} bind). The
     * resolved value of each placeholder is screened for SQL meta-characters (defense in depth — the
     * value should already be {@code enum}-constrained at the input layer); the template's literal
     * text, being author-written, is emitted as-is.
     */
    private void appendEmbedded(SqlNode.Embedded embedded) {
        Matcher matcher = PLACEHOLDER.matcher(embedded.template());
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1).trim();
            Object value = context.resolve(Arrays.asList(path.split("\\.")));
            String text = value == null ? "" : String.valueOf(value);
            guardEmbedded(text, path, embedded.sourceLine());
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(text));
        }
        matcher.appendTail(rendered);
        mapToSource(rendered.toString(), embedded.sourceLine());
        coverage.coverLine(embedded.sourceLine());
    }

    /** Rejects an interpolated value that could break out of its SQL position (Doma-style guard). */
    private void guardEmbedded(String value, String path, int sourceLine) {
        boolean unsafe = value.indexOf('\'') >= 0 || value.indexOf(';') >= 0
                || value.contains("--") || value.contains("/*") || value.contains("*/");
        for (int i = 0; !unsafe && i < value.length(); i++) {
            unsafe = Character.isISOControl(value.charAt(i));
        }
        if (unsafe) {
            throw TqlException.builder(UNSAFE_EMBEDDED)
                    .message("Embedded variable '" + path + "' resolved to a value with SQL "
                            + "meta-characters or control characters; constrain it with an "
                            + "enum input. Value: " + value)
                    .line(sourceLine)
                    .build();
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
                    listBind.expressionSource() + "[" + i + "]", elements.get(i),
                    listBind.sourceLine()));
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
