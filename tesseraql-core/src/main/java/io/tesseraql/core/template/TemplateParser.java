package io.tesseraql.core.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@link HtmlTemplateEngine} template source into a {@link TemplateNode} tree.
 */
final class TemplateParser {

    private final List<Token> tokens;
    private int position;

    private TemplateParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    static List<TemplateNode> parse(String source) {
        TemplateParser parser = new TemplateParser(tokenize(source));
        List<TemplateNode> nodes = parser.parseNodes(null);
        if (parser.position < parser.tokens.size()) {
            throw HtmlTemplateEngine.error("Unexpected section end in template");
        }
        return nodes;
    }

    private List<TemplateNode> parseNodes(String closing) {
        List<TemplateNode> nodes = new ArrayList<>();
        while (position < tokens.size()) {
            Token token = tokens.get(position);
            switch (token.type()) {
                case TEXT -> {
                    nodes.add(new TemplateNode.Text(token.content()));
                    position++;
                }
                case VARIABLE -> {
                    nodes.add(new TemplateNode.Variable(token.content(), true));
                    position++;
                }
                case RAW -> {
                    nodes.add(new TemplateNode.Variable(token.content(), false));
                    position++;
                }
                case COMMENT -> position++;
                case SECTION, INVERTED -> {
                    position++;
                    List<TemplateNode> body = parseNodes(token.content());
                    nodes.add(new TemplateNode.Section(
                            token.content(), token.type() == TokenType.INVERTED, body));
                }
                case END -> {
                    if (!token.content().equals(closing)) {
                        throw HtmlTemplateEngine.error(
                                "Mismatched section end: expected " + closing + ", found " + token.content());
                    }
                    position++;
                    return nodes;
                }
                default -> throw HtmlTemplateEngine.error("Unexpected token in template");
            }
        }
        if (closing != null) {
            throw HtmlTemplateEngine.error("Unclosed section: " + closing);
        }
        return nodes;
    }

    private static List<Token> tokenize(String source) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            int open = source.indexOf("{{", index);
            if (open < 0) {
                tokens.add(new Token(TokenType.TEXT, source.substring(index)));
                break;
            }
            if (open > index) {
                tokens.add(new Token(TokenType.TEXT, source.substring(index, open)));
            }
            boolean raw = source.startsWith("{{{", open);
            String close = raw ? "}}}" : "}}";
            int end = source.indexOf(close, open + (raw ? 3 : 2));
            if (end < 0) {
                throw HtmlTemplateEngine.error("Unterminated template tag");
            }
            String inner = source.substring(open + (raw ? 3 : 2), end).trim();
            tokens.add(toTag(raw, inner));
            index = end + close.length();
        }
        return tokens;
    }

    private static Token toTag(boolean raw, String inner) {
        if (raw) {
            return new Token(TokenType.RAW, inner);
        }
        if (inner.isEmpty()) {
            return new Token(TokenType.COMMENT, "");
        }
        char marker = inner.charAt(0);
        String rest = inner.substring(1).trim();
        return switch (marker) {
            case '#' -> new Token(TokenType.SECTION, rest);
            case '^' -> new Token(TokenType.INVERTED, rest);
            case '/' -> new Token(TokenType.END, rest);
            case '!' -> new Token(TokenType.COMMENT, "");
            default -> new Token(TokenType.VARIABLE, inner);
        };
    }

    private enum TokenType {
        TEXT, VARIABLE, RAW, SECTION, INVERTED, END, COMMENT
    }

    private record Token(TokenType type, String content) {
    }
}
