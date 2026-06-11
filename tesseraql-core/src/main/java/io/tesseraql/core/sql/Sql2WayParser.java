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
 *   <li>{@code /*%for item : items *}{@code / ... /*%end*}{@code /}</li>
 * </ul>
 *
 * <p>Every {@code /* ... *}{@code /} block comment is treated as a directive (Doma-style 2-way SQL
 * convention). Use {@code --} line comments for non-directive remarks.
 */
public final class Sql2WayParser {

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
            if (peekCommentStart()) {
                flushText(nodes, text, textStartLine);
                Directive directive = readComment();
                if (directive.control()) {
                    switch (directive.keyword()) {
                        case "if" -> nodes.add(parseIf(directive));
                        case "for" -> nodes.add(parseFor(directive));
                        case "elseif", "else", "end" -> {
                            pendingTerminator = directive;
                            return nodes;
                        }
                        default -> throw error("Unknown directive '" + directive.keyword() + "'");
                    }
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

    private SqlNode parseBind(Directive directive) {
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

    private SqlNode parseIf(Directive first) {
        List<SqlNode.If.Branch> branches = new ArrayList<>();
        branches.add(new SqlNode.If.Branch(
                ExpressionParser.parse(first.argument("if")), first.sourceLine(), parseBlock()));
        while (true) {
            Directive terminator = requireTerminator("if");
            switch (terminator.keyword()) {
                case "elseif" -> branches.add(new SqlNode.If.Branch(
                        ExpressionParser.parse(terminator.argument("elseif")),
                        terminator.sourceLine(), parseBlock()));
                case "else" -> branches.add(new SqlNode.If.Branch(
                        null, terminator.sourceLine(), parseBlock()));
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
        if (itemVar.isEmpty() || listExpr.isEmpty()) {
            throw error("for directive must be 'item : items'");
        }
        List<SqlNode> body = parseBlock();
        Directive terminator = requireTerminator("for");
        if (!"end".equals(terminator.keyword())) {
            throw error("Expected end for 'for', found '" + terminator.keyword() + "'");
        }
        pendingTerminator = null;
        return new SqlNode.For(itemVar, listExpr, ExpressionParser.parse(listExpr),
                first.sourceLine(), body);
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

    private boolean peekCommentStart() {
        return source.charAt(pos) == '/' && pos + 1 < length && source.charAt(pos + 1) == '*';
    }

    private Directive readComment() {
        int directiveLine = line;
        pos += 2; // consume "/*"
        boolean control = pos < length && source.charAt(pos) == '%';
        if (control) {
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
        return new Directive(control, content.toString().trim(), directiveLine);
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

    private record Directive(boolean control, String content, int sourceLine) {
        String keyword() {
            int space = content.indexOf(' ');
            return space < 0 ? content : content.substring(0, space);
        }

        String argument(String keyword) {
            return content.substring(keyword.length()).trim();
        }
    }
}
