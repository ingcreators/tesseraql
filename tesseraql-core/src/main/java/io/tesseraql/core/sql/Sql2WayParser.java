package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionParser;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses TesseraQL 2-way SQL into a {@link SqlNode} tree (design ch. 8.1).
 *
 * <p>Supported directives, all written as SQL block comments so the template stays executable in
 * an ordinary SQL tool:
 * <ul>
 *   <li>{@code /* expr *}{@code / dummy} — scalar bind; the dummy literal is replaced by {@code ?}</li>
 *   <li>{@code /* expr *}{@code / (...)} — IN-list bind; expands to {@code (?, ?, ...)}</li>
 *   <li>{@code /*%if cond *}{@code / ... /*%elseif cond *}{@code / ... /*%else *}{@code / ... /*%end*}{@code /}</li>
 *   <li>{@code /*%for item : items *}{@code / ... /*%end*}{@code /} — optionally
 *       {@code /*%for item : items separator ',' *}{@code /}; the loop exposes
 *       {@code item_index} (0-based) alongside {@code item}</li>
 *   <li>{@code /*# template *}{@code /} — embedded variable; {@code {placeholder}} references in the
 *       template are interpolated into the SQL text (not bound as {@code ?}), for identifier-position
 *       fragments a bind cannot drive such as a dynamic {@code ORDER BY}. The whole fragment lives in
 *       the comment, so the statement stays SQL-tool-runnable; placeholders must be enum-constrained
 *       (see {@link SqlNode.Embedded})</li>
 * </ul>
 *
 * <p>Every {@code /* ... *}{@code /} block comment is treated as a directive (Doma-style 2-way SQL
 * convention). Use {@code --} line comments for non-directive remarks.
 */
public final class Sql2WayParser {

    /** TQL-SQL-2102: a 2-way SQL file could not be parsed; the message names the offending line. */
    private static final TqlErrorCode PARSE_ERROR = new TqlErrorCode(TqlDomain.SQL, 2102);

    private final String source;
    private final int length;
    private int pos;
    private int line = 1;
    private Directive pendingTerminator;

    private Sql2WayParser(String source) {
        this.source = source;
        this.length = source.length();
    }

    /** Parses a 2-way SQL template string into its node tree. */
    public static List<SqlNode> parse(String source) {
        Sql2WayParser parser = new Sql2WayParser(source);
        List<SqlNode> nodes = parser.parseBlock();
        if (parser.pendingTerminator != null) {
            throw parser.error("Unexpected '" + parser.pendingTerminator.keyword()
                    + "' without matching block");
        }
        return nodes;
    }

    private List<SqlNode> parseBlock() {
        List<SqlNode> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int textStartLine = line;
        pendingTerminator = null;

        while (pos < length) {
            if (pos + 1 < length && source.charAt(pos) == '-' && source.charAt(pos + 1) == '-') {
                // A -- line comment is opaque non-directive text (the documented convention);
                // an apostrophe inside one (-- don't ...) must not open a string literal.
                while (pos < length && source.charAt(pos) != '\n') {
                    text.append(consume());
                }
                continue;
            }
            if (pos < length && source.charAt(pos) == '\'') {
                // A quoted SQL string is opaque: a /* inside it (a glob like 's3://x/**', a LIKE
                // pattern) is content, not a directive. '' stays the escape for a literal quote.
                consumeStringLiteral(text);
                continue;
            }
            if (peekCommentStart()) {
                flushText(nodes, text, textStartLine);
                Directive directive = readComment();
                if (directive.control()) {
                    switch (directive.keyword()) {
                        case "if" -> nodes.add(parseIf(directive));
                        case "for" -> nodes.add(parseFor(directive));
                        case "scope" -> nodes.add(parseScope(directive));
                        case "elseif", "else", "end" -> {
                            pendingTerminator = directive;
                            return nodes;
                        }
                        default -> throw error("Unknown directive '" + directive.keyword() + "'");
                    }
                } else if (directive.embedded()) {
                    nodes.add(parseEmbedded(directive));
                } else {
                    nodes.add(parseBind(directive));
                }
                textStartLine = line;
            } else {
                text.append(consume());
            }
        }
        flushText(nodes, text, textStartLine);
        return nodes;
    }

    private SqlNode parseEmbedded(Directive directive) {
        String template = directive.content();
        if (template.isEmpty()) {
            throw error("Empty embedded variable");
        }
        // No dummy follows: the whole fragment lives inside the comment, so the surrounding
        // statement stays runnable in a plain SQL tool. The renderer interpolates {placeholder}s.
        return new SqlNode.Embedded(template, directive.sourceLine());
    }

    private SqlNode parseBind(Directive directive) {
        if (directive.content().trim().startsWith("${")) {
            return parseFilePath(directive);
        }
        boolean list = skipWhitespacePeek() == '(';
        skipDummy(list);
        String expr = directive.content().trim();
        if (expr.isEmpty()) {
            throw error("Empty bind expression");
        }
        return list
                ? new SqlNode.ListBind(expr, ExpressionParser.parse(expr), directive.sourceLine())
                : new SqlNode.Bind(expr, ExpressionParser.parse(expr), directive.sourceLine());
    }

    /**
     * A file-reference site: {@code ${scope.name}/rel/path} or {@code ${dataset.param}}
     * (docs/duckdb.md). Shape-validated here so a traversal or meta-character never reaches the
     * renderer; the dummy literal that follows is consumed like any bind's.
     */
    private SqlNode parseFilePath(Directive directive) {
        skipDummy(false);
        String content = directive.content().trim();
        int close = content.indexOf('}');
        if (!content.startsWith("${") || close < 0) {
            throw error("Malformed file placeholder '" + content + "': expected ${scope.name} or"
                    + " ${dataset.param}");
        }
        String reference = content.substring(2, close);
        String suffix = content.substring(close + 1);
        int dot = reference.indexOf('.');
        String channel = dot < 0 ? reference : reference.substring(0, dot);
        String name = dot < 0 ? "" : reference.substring(dot + 1);
        if (!("scope".equals(channel) || "dataset".equals(channel))
                || !name.matches("[A-Za-z0-9_-]+")) {
            throw error("Unknown file placeholder '" + content
                    + "': only ${scope.<name>} and ${dataset.<param>} resolve to files");
        }
        if ("dataset".equals(channel) && !suffix.isEmpty()) {
            throw error("A ${dataset.*} placeholder names a whole file; '" + suffix
                    + "' cannot follow it");
        }
        if ("scope".equals(channel) && !validScopeSuffix(suffix)) {
            throw error("File placeholder path '" + suffix + "' must be /-separated relative"
                    + " segments of [A-Za-z0-9._*-] with no '..'");
        }
        return new SqlNode.FilePath(channel, name, suffix, directive.sourceLine());
    }

    /** {@code /seg/seg} where each segment is safe charset, non-empty, and never {@code ..}. */
    private static boolean validScopeSuffix(String suffix) {
        if (suffix.isEmpty()) {
            return true;
        }
        if (!suffix.startsWith("/")) {
            return false;
        }
        for (String segment : suffix.substring(1).split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || !segment.matches("[A-Za-z0-9._*-]+")) {
                return false;
            }
        }
        return true;
    }

    private SqlNode parseIf(Directive first) {
        List<SqlNode.If.Branch> branches = new ArrayList<>();
        String ifCondition = first.argument("if");
        branches.add(new SqlNode.If.Branch(ExpressionParser.parse(ifCondition), ifCondition,
                first.sourceLine(), parseBlock()));
        while (true) {
            Directive terminator = requireTerminator("if");
            switch (terminator.keyword()) {
                case "elseif" -> {
                    String elseifCondition = terminator.argument("elseif");
                    branches.add(new SqlNode.If.Branch(ExpressionParser.parse(elseifCondition),
                            elseifCondition, terminator.sourceLine(), parseBlock()));
                }
                case "else" -> branches.add(new SqlNode.If.Branch(
                        null, null, terminator.sourceLine(), parseBlock()));
                case "end" -> {
                    pendingTerminator = null;
                    return new SqlNode.If(branches);
                }
                default ->
                    throw error("Expected elseif/else/end, found '" + terminator.keyword() + "'");
            }
        }
    }

    private SqlNode parseFor(Directive first) {
        String argument = first.argument("for");
        int colon = argument.indexOf(':');
        if (colon < 0) {
            throw error("for directive must be 'item : items'");
        }
        String itemVar = argument.substring(0, colon).trim();
        String listExpr = argument.substring(colon + 1).trim();
        // An optional separator keeps multi-row templates SQL-tool-runnable: the separator
        // lives inside the directive comment, never in the raw SQL text.
        String separator = null;
        int keyword = listExpr.lastIndexOf(" separator ");
        if (keyword >= 0) {
            String literal = listExpr.substring(keyword + " separator ".length()).trim();
            if (literal.length() < 2 || literal.charAt(0) != '\''
                    || literal.charAt(literal.length() - 1) != '\'') {
                throw error("for separator must be a quoted literal, e.g. separator ','");
            }
            separator = literal.substring(1, literal.length() - 1);
            listExpr = listExpr.substring(0, keyword).trim();
        }
        if (itemVar.isEmpty() || listExpr.isEmpty()) {
            throw error("for directive must be 'item : items'");
        }
        List<SqlNode> body = parseBlock();
        Directive terminator = requireTerminator("for");
        if (!"end".equals(terminator.keyword())) {
            throw error("Expected end for 'for', found '" + terminator.keyword() + "'");
        }
        pendingTerminator = null;
        return new SqlNode.For(itemVar, listExpr, ExpressionParser.parse(listExpr), separator,
                first.sourceLine(), body);
    }

    private SqlNode parseScope(Directive directive) {
        String argument = directive.argument("scope").trim();
        // `as boolean` renders the scope as a SELECT-list flag (case when … then 1 else 0 end) for
        // row-level masking, rather than a WHERE predicate (roadmap Phase 29 slice 3).
        boolean asBoolean = argument.endsWith(" as boolean");
        if (asBoolean) {
            argument = argument.substring(0, argument.length() - " as boolean".length()).trim();
        }
        String name = argument;
        String alias = null;
        int on = argument.indexOf(" on ");
        if (on >= 0) {
            name = argument.substring(0, on).trim();
            alias = argument.substring(on + " on ".length()).trim();
        }
        if (name.isEmpty()) {
            throw error("scope directive needs a scope name");
        }
        if (alias != null && !alias.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw error("scope 'on' alias '" + alias + "' must be a SQL identifier");
        }
        // A scope directive replaces a parenthesized dummy predicate so the template stays runnable
        // in a plain SQL tool (where it reads as `(1=1)`); the resolved scope predicate takes its
        // place at render time. Requiring the parentheses keeps the boundary unambiguous.
        if (skipWhitespacePeek() != '(') {
            throw error("a scope directive must be followed by a parenthesized dummy predicate, "
                    + "e.g. /*%scope " + name + " */ (1=1)");
        }
        skipParenGroup();
        return new SqlNode.Scope(name, alias, asBoolean, directive.sourceLine());
    }

    private Directive requireTerminator(String block) {
        Directive terminator = pendingTerminator;
        if (terminator == null) {
            throw error("Unterminated '" + block + "' block");
        }
        return terminator;
    }

    private void flushText(List<SqlNode> nodes, StringBuilder text, int startLine) {
        if (text.length() > 0) {
            nodes.add(new SqlNode.Text(text.toString(), startLine));
            text.setLength(0);
        }
    }

    /** Consumes a complete {@code '...'} literal (with {@code ''} escapes) into {@code text}. */
    private void consumeStringLiteral(StringBuilder text) {
        text.append(consume());
        while (pos < length) {
            char c = consume();
            text.append(c);
            if (c == '\'') {
                if (pos < length && source.charAt(pos) == '\'') {
                    text.append(consume());
                    continue;
                }
                return;
            }
        }
        throw error("Unterminated string literal");
    }

    private boolean peekCommentStart() {
        return source.charAt(pos) == '/' && pos + 1 < length && source.charAt(pos + 1) == '*';
    }

    private Directive readComment() {
        int directiveLine = line;
        pos += 2; // consume "/*"
        // A leading '%' marks a control directive (/*%if%/ …); a leading '#' an embedded variable
        // (/*# … %/, Doma-style); anything else is a bind site (/* expr %/ dummy).
        boolean control = pos < length && source.charAt(pos) == '%';
        boolean embedded = !control && pos < length && source.charAt(pos) == '#';
        if (control || embedded) {
            pos++;
        }
        StringBuilder content = new StringBuilder();
        while (pos < length && !(source.charAt(pos) == '*' && pos + 1 < length
                && source.charAt(pos + 1) == '/')) {
            content.append(consume());
        }
        if (pos >= length) {
            throw error("Unterminated comment");
        }
        pos += 2; // consume "*/"
        return new Directive(control, embedded, content.toString().trim(), directiveLine);
    }

    private void skipDummy(boolean list) {
        skipWhitespacePeek();
        if (pos >= length) {
            return;
        }
        if (list) {
            skipParenGroup();
            return;
        }
        char c = source.charAt(pos);
        if (c == '\'' || c == '"') {
            skipQuoted(c);
        } else if (Character.isDigit(c) || c == '+' || c == '-' || c == '.') {
            while (pos < length && (Character.isDigit(source.charAt(pos))
                    || ".+-eE".indexOf(source.charAt(pos)) >= 0)) {
                consume();
            }
        } else {
            while (pos < length && Character.isJavaIdentifierPart(source.charAt(pos))) {
                consume();
            }
        }
    }

    private void skipParenGroup() {
        int depth = 0;
        do {
            char c = consume();
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '\'' || c == '"') {
                skipQuoted(c);
            }
        } while (depth > 0 && pos < length);
    }

    private void skipQuoted(char quote) {
        consume(); // opening quote
        while (pos < length) {
            char c = consume();
            if (c == quote) {
                return;
            }
        }
    }

    private char skipWhitespacePeek() {
        while (pos < length && Character.isWhitespace(source.charAt(pos))) {
            consume();
        }
        return pos < length ? source.charAt(pos) : '\0';
    }

    private char consume() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
        }
        return c;
    }

    private TqlException error(String message) {
        return TqlException.builder(PARSE_ERROR).message(message).line(line).build();
    }

    private record Directive(boolean control, boolean embedded, String content, int sourceLine) {
        String keyword() {
            int space = content.indexOf(' ');
            return space < 0 ? content : content.substring(0, space);
        }

        String argument(String keyword) {
            return content.substring(keyword.length()).trim();
        }
    }
}
