package io.tesseraql.core.expr;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser for 2-way SQL directive expressions (design ch. 8.1).
 *
 * <p>Grammar, lowest to highest precedence:
 * <pre>
 *   or             : and ( '||' and )*
 *   and            : equality ( '&amp;&amp;' equality )*
 *   equality       : comparison ( ('==' | '!=') comparison )*
 *   comparison     : additive ( ('&lt;' | '&gt;' | '&lt;=' | '&gt;=') additive )*
 *   additive       : multiplicative ( ('+' | '-') multiplicative )*
 *   multiplicative : unary ( ('*' | '/' | '%') unary )*
 *   unary          : '!' unary | '-' unary | primary
 *   primary        : literal | call | path | '(' or ')'
 *   call           : FUNCTION '(' or ( ',' or )* ')'
 * </pre>
 *
 * Hand-written with no external dependency to keep {@code tesseraql-core} dependency-free.
 */
public final class ExpressionParser {

    private static final TqlErrorCode SYNTAX_ERROR = new TqlErrorCode(TqlDomain.SQL, 2101);

    private final List<Token> tokens;
    private int position;

    private ExpressionParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    /** Parses an expression source string into an {@link Expr} tree. */
    public static Expr parse(String source) {
        List<Token> tokens = new Lexer(source).tokenize();
        ExpressionParser parser = new ExpressionParser(tokens);
        Expr expr = parser.or();
        if (!parser.atEnd()) {
            throw parser.error("Unexpected token '" + parser.peek().text() + "'");
        }
        return expr;
    }

    private Expr or() {
        Expr left = and();
        while (match(TokenType.OR)) {
            left = new Expr.Logical(Expr.Logical.Operator.OR, left, and());
        }
        return left;
    }

    private Expr and() {
        Expr left = equality();
        while (match(TokenType.AND)) {
            left = new Expr.Logical(Expr.Logical.Operator.AND, left, equality());
        }
        return left;
    }

    private Expr equality() {
        Expr left = comparison();
        while (true) {
            if (match(TokenType.EQ)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.EQ, left, comparison());
            } else if (match(TokenType.NE)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.NE, left, comparison());
            } else {
                return left;
            }
        }
    }

    private Expr comparison() {
        Expr left = additive();
        while (true) {
            if (match(TokenType.LT)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.LT, left, additive());
            } else if (match(TokenType.GT)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.GT, left, additive());
            } else if (match(TokenType.LE)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.LE, left, additive());
            } else if (match(TokenType.GE)) {
                left = new Expr.Comparison(Expr.Comparison.Operator.GE, left, additive());
            } else {
                return left;
            }
        }
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (true) {
            if (match(TokenType.PLUS)) {
                left = new Expr.Arithmetic(Expr.Arithmetic.Operator.ADD, left, multiplicative());
            } else if (match(TokenType.MINUS)) {
                left = new Expr.Arithmetic(Expr.Arithmetic.Operator.SUB, left, multiplicative());
            } else {
                return left;
            }
        }
    }

    private Expr multiplicative() {
        Expr left = unary();
        while (true) {
            if (match(TokenType.STAR)) {
                left = new Expr.Arithmetic(Expr.Arithmetic.Operator.MUL, left, unary());
            } else if (match(TokenType.SLASH)) {
                left = new Expr.Arithmetic(Expr.Arithmetic.Operator.DIV, left, unary());
            } else if (match(TokenType.PERCENT)) {
                left = new Expr.Arithmetic(Expr.Arithmetic.Operator.MOD, left, unary());
            } else {
                return left;
            }
        }
    }

    private Expr unary() {
        if (match(TokenType.NOT)) {
            return new Expr.Not(unary());
        }
        if (match(TokenType.MINUS)) {
            return new Expr.Negate(unary());
        }
        return primary();
    }

    private Expr primary() {
        if (match(TokenType.LPAREN)) {
            Expr expr = or();
            expect(TokenType.RPAREN, "Expected ')'");
            return expr;
        }
        Token token = advance();
        return switch (token.type()) {
            case STRING -> new Expr.Literal(token.text());
            case NUMBER -> new Expr.Literal(parseNumber(token.text()));
            case IDENT -> identifierExpr(token);
            default -> throw error("Unexpected token '" + token.text() + "'");
        };
    }

    private Expr identifierExpr(Token first) {
        switch (first.text()) {
            case "null" -> {
                return new Expr.Literal(null);
            }
            case "true" -> {
                return new Expr.Literal(Boolean.TRUE);
            }
            case "false" -> {
                return new Expr.Literal(Boolean.FALSE);
            }
            default -> {
                // A function call (roadmap Phase 40): IDENT '(' args ')'. The name must be a
                // built-in or an installed ExpressionFunction, and the arity must match —
                // unknown names and wrong arities are parse errors, so lint catches them at
                // build. Only calls use '(', so an unknown name here is always a mistake.
                if (!atEnd() && peek().type() == TokenType.LPAREN) {
                    Integer arity = ExpressionFunctions.arity(first.text());
                    if (arity == null) {
                        throw error("Unknown function '" + first.text() + "()' — not a"
                                + " built-in and no installed custom function of that name"
                                + " (custom functions load from tesseraql.modules /"
                                + " --modules)");
                    }
                    advance();
                    List<Expr> args = new ArrayList<>();
                    if (!match(TokenType.RPAREN)) {
                        args.add(or());
                        while (match(TokenType.COMMA)) {
                            args.add(or());
                        }
                        expect(TokenType.RPAREN, "Expected ')' after arguments");
                    }
                    if (args.size() != arity) {
                        throw error(first.text() + "() takes " + arity + " argument"
                                + (arity == 1 ? "" : "s") + ", got " + args.size());
                    }
                    return new Expr.Call(first.text(), args);
                }
                List<String> segments = new ArrayList<>();
                segments.add(first.text());
                while (match(TokenType.DOT)) {
                    segments.add(
                            expect(TokenType.IDENT, "Expected property name after '.'").text());
                }
                return new Expr.Path(segments);
            }
        }
    }

    private static Object parseNumber(String text) {
        if (text.indexOf('.') >= 0) {
            return Double.parseDouble(text);
        }
        return Long.parseLong(text);
    }

    private boolean match(TokenType type) {
        if (!atEnd() && peek().type() == type) {
            position++;
            return true;
        }
        return false;
    }

    private Token expect(TokenType type, String message) {
        if (!atEnd() && peek().type() == type) {
            return advance();
        }
        throw error(message);
    }

    private Token advance() {
        if (atEnd()) {
            throw error("Unexpected end of expression");
        }
        return tokens.get(position++);
    }

    private Token peek() {
        return tokens.get(position);
    }

    private boolean atEnd() {
        return position >= tokens.size();
    }

    private TqlException error(String message) {
        return new TqlException(SYNTAX_ERROR, message);
    }

    private enum TokenType {
        IDENT, STRING, NUMBER, DOT, AND, OR, NOT, EQ, NE, LT, GT, LE, GE, LPAREN, RPAREN, PLUS, MINUS, STAR, SLASH, PERCENT, COMMA
    }

    private record Token(TokenType type, String text) {
    }

    /** Tokenizer for the expression grammar. */
    private static final class Lexer {
        private final String source;
        private int index;

        Lexer(String source) {
            this.source = source;
        }

        List<Token> tokenize() {
            List<Token> tokens = new ArrayList<>();
            while (index < source.length()) {
                char c = source.charAt(index);
                if (Character.isWhitespace(c)) {
                    index++;
                } else if (c == '"' || c == '\'') {
                    tokens.add(string(c));
                } else if (Character.isDigit(c)) {
                    tokens.add(number());
                } else if (Character.isJavaIdentifierStart(c)) {
                    tokens.add(identifier());
                } else {
                    tokens.add(operator());
                }
            }
            return tokens;
        }

        private Token string(char quote) {
            int start = ++index;
            StringBuilder sb = new StringBuilder();
            while (index < source.length() && source.charAt(index) != quote) {
                char c = source.charAt(index);
                if (c == '\\' && index + 1 < source.length()) {
                    index++;
                    sb.append(source.charAt(index));
                } else {
                    sb.append(c);
                }
                index++;
            }
            if (index >= source.length()) {
                throw new TqlException(SYNTAX_ERROR, "Unterminated string literal at " + start);
            }
            index++;
            return new Token(TokenType.STRING, sb.toString());
        }

        private Token number() {
            int start = index;
            while (index < source.length()
                    && (Character.isDigit(source.charAt(index)) || source.charAt(index) == '.')) {
                index++;
            }
            return new Token(TokenType.NUMBER, source.substring(start, index));
        }

        private Token identifier() {
            int start = index;
            while (index < source.length()
                    && Character.isJavaIdentifierPart(source.charAt(index))) {
                index++;
            }
            return new Token(TokenType.IDENT, source.substring(start, index));
        }

        private Token operator() {
            char c = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (c) {
                case '.' -> {
                    index++;
                    return new Token(TokenType.DOT, ".");
                }
                case '(' -> {
                    index++;
                    return new Token(TokenType.LPAREN, "(");
                }
                case ')' -> {
                    index++;
                    return new Token(TokenType.RPAREN, ")");
                }
                case '+' -> {
                    index++;
                    return new Token(TokenType.PLUS, "+");
                }
                case '-' -> {
                    index++;
                    return new Token(TokenType.MINUS, "-");
                }
                case '*' -> {
                    index++;
                    return new Token(TokenType.STAR, "*");
                }
                case '/' -> {
                    index++;
                    return new Token(TokenType.SLASH, "/");
                }
                case '%' -> {
                    index++;
                    return new Token(TokenType.PERCENT, "%");
                }
                case ',' -> {
                    index++;
                    return new Token(TokenType.COMMA, ",");
                }
                case '&' -> {
                    return twoChar(next, '&', TokenType.AND, "&&");
                }
                case '|' -> {
                    return twoChar(next, '|', TokenType.OR, "||");
                }
                case '=' -> {
                    return twoChar(next, '=', TokenType.EQ, "==");
                }
                case '!' -> {
                    if (next == '=') {
                        index += 2;
                        return new Token(TokenType.NE, "!=");
                    }
                    index++;
                    return new Token(TokenType.NOT, "!");
                }
                case '<' -> {
                    if (next == '=') {
                        index += 2;
                        return new Token(TokenType.LE, "<=");
                    }
                    index++;
                    return new Token(TokenType.LT, "<");
                }
                case '>' -> {
                    if (next == '=') {
                        index += 2;
                        return new Token(TokenType.GE, ">=");
                    }
                    index++;
                    return new Token(TokenType.GT, ">");
                }
                default -> throw new TqlException(SYNTAX_ERROR, "Unexpected character '" + c + "'");
            }
        }

        private Token twoChar(char next, char expected, TokenType type, String text) {
            if (next != expected) {
                throw new TqlException(SYNTAX_ERROR, "Expected '" + text + "'");
            }
            index += 2;
            return new Token(type, text);
        }
    }
}
