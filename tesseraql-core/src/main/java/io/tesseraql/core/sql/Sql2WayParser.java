package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *   <li>{@code /*%lock*}{@code / (1=1)} — the optimistic-lock comparison an
 *       {@code update} command's WHERE carries; the framework compares the declared column
 *       against the value the caller sent back, and the statement itself advances it
 *       (docs/edit-conflict.md)</li>
 *   <li>{@code /*# template *}{@code /} — embedded variable; {@code {placeholder}} references in the
 *       template are interpolated into the SQL text (not bound as {@code ?}), for identifier-position
 *       fragments a bind cannot drive such as a dynamic {@code ORDER BY}. The whole fragment lives in
 *       the comment, so the statement stays SQL-tool-runnable; placeholders must be enum-constrained
 *       (see {@link SqlNode.Embedded})</li>
 * </ul>
 *
 * <p>Every {@code /* ... *}{@code /} block comment is treated as a directive (Doma-style 2-way SQL
 * convention). Use {@code --} line comments for non-directive remarks.
 *
 * <p>Four things are opaque to that rule, so what they contain is content rather than syntax: a
 * {@code --} line comment, a {@code '...'} string literal, and a {@code "..."} or
 * {@code `...`} quoted identifier. A {@code /*}, an apostrophe or a {@code --} inside any of them
 * is text. {@code [} is <em>not</em> opaque — in DuckDB and PostgreSQL it is list and array
 * syntax, not quoting (docs/two-way-sql-parser.md decision 5).
 */
public final class Sql2WayParser {

    /** TQL-SQL-2102: a 2-way SQL file could not be parsed; the message names the offending line. */
    private static final TqlErrorCode PARSE_ERROR = new TqlErrorCode(TqlDomain.SQL, 2102);

    /**
     * The most directive nesting ({@code /*%if*}{@code /} / {@code /*%for*}{@code /} /
     * {@code /*%scope*}{@code /}) a template may carry. Far above any real template, far below
     * the recursion depth that overflows the stack: a hostile deep-nested template gets a coded
     * parse rejection here instead of a fatal {@link StackOverflowError} (docs/security-hardening.md).
     */
    private static final int MAX_NESTING_DEPTH = 200;

    /** The {@code for} directive's trailing {@code separator} clause, whitespace-separated. */
    private static final Pattern SEPARATOR = Pattern.compile("\\s+separator\\s+");
    /**
     * The type keywords a standard typed literal may open with, so {@code DATE '2024-01-01'} is
     * one dummy while {@code select /* x *}{@code / x 'alias'} keeps the alias some dialects
     * write as a string (docs/two-way-sql-parser.md decision 3).
     */
    private static final Set<String> TYPE_KEYWORDS = Set.of("date", "time", "timestamp",
            "timestamptz", "datetime", "interval", "decimal", "numeric", "uuid", "json", "jsonb",
            "bytea", "bit", "binary");

    private final String source;
    private final int length;
    private final io.tesseraql.core.expr.ExpressionFunctions functions;
    private int pos;
    private int line = 1;
    private int depth;
    private Directive pendingTerminator;

    private Sql2WayParser(String source, io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.source = source;
        this.length = source.length();
        this.functions = functions;
    }

    /**
     * Parses a 2-way SQL template string into its node tree, resolving directive expressions
     * against the process-default function set.
     */
    public static List<SqlNode> parse(String source) {
        return parse(source, io.tesseraql.core.expr.ExpressionFunctions.processDefault());
    }

    /** Parses a 2-way SQL template string, resolving expressions against {@code functions}. */
    public static List<SqlNode> parse(String source,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        Sql2WayParser parser = new Sql2WayParser(source, functions);
        List<SqlNode> nodes = parser.parseBlock();
        if (parser.pendingTerminator != null) {
            throw parser.error("Unexpected '" + parser.pendingTerminator.keyword()
                    + "' without matching block");
        }
        return nodes;
    }

    private List<SqlNode> parseBlock() {
        if (++depth > MAX_NESTING_DEPTH) {
            throw error("Directive nesting too deep (over " + MAX_NESTING_DEPTH + ")");
        }
        try {
            return parseBlockBody();
        } finally {
            depth--;
        }
    }

    private List<SqlNode> parseBlockBody() {
        List<SqlNode> nodes = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int textStartLine = line;
        pendingTerminator = null;

        while (pos < length) {
            char c = source.charAt(pos);
            if (c == '-' && pos + 1 < length && source.charAt(pos + 1) == '-') {
                // A -- line comment is opaque non-directive text (the documented convention);
                // an apostrophe inside one (-- don't ...) must not open a string literal.
                while (pos < length && source.charAt(pos) != '\n') {
                    text.append(consume());
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                // A quoted run is opaque whichever delimiter opens it: a /* inside it (a glob
                // like 's3://x/**', a LIKE pattern, an alias written "a/*b") is content and not
                // a directive, and a -- inside it is not a line comment. Backticks join because
                // MySQL is a supported dialect and `order` is its idiomatic quoting.
                //
                // '[' deliberately does not: in DuckDB and PostgreSQL it is list and array
                // syntax, so reading it as SQL Server bracket quoting would swallow a directive
                // written inside an array constructor (docs/two-way-sql-parser.md decision 5).
                consumeQuotedRun(text, c);
                continue;
            }
            if (peekCommentStart()) {
                flushText(nodes, text, textStartLine);
                int commentStart = pos;
                Directive directive = readComment();
                if (directive.control()) {
                    switch (directive.keyword()) {
                        case "if" -> nodes.add(parseIf(directive));
                        case "for" -> nodes.add(parseFor(directive));
                        case "scope" -> nodes.add(parseScope(directive));
                        case "lock" -> nodes.add(parseLock(directive));
                        case "elseif", "else", "end" -> {
                            pendingTerminator = directive;
                            return nodes;
                        }
                        default -> throw error("Unknown directive '" + directive.keyword() + "'");
                    }
                } else if (directive.embedded()) {
                    nodes.add(parseEmbedded(directive));
                } else {
                    nodes.add(parseBind(directive, commentStart));
                }
                textStartLine = line;
            } else {
                text.append(consume());
            }
        }
        flushText(nodes, text, textStartLine);
        return nodes;
    }

    /**
     * Whether the list bind opening at {@code commentStart} sits under {@code NOT IN}. The
     * renderer is handed the list and never the operator, and an empty list renders {@code (null)}
     * — right under {@code IN} and exactly inverted under {@code NOT IN}
     * (docs/two-way-sql-parser.md decision 9).
     *
     * <p>It scans backwards over the raw source rather than the buffered text, because the text
     * buffer is empty when the previous node was a directive. A {@code not} whose preceding
     * character is an identifier part is part of a longer word ({@code is_not}) and not the
     * operator. A {@code NOT IN} split across a directive boundary is not detected; that is a
     * recorded deviation, not an oversight.
     */
    private boolean precededByNotIn(int commentStart) {
        int at = skipBackWhitespace(commentStart - 1);
        at = matchBack(at, "in");
        if (at == NO_MATCH) {
            return false;
        }
        int beforeIn = skipBackWhitespace(at);
        if (beforeIn == at) {
            return false; // `in` must be a word of its own
        }
        at = matchBack(beforeIn, "not");
        if (at == NO_MATCH) {
            return false;
        }
        return at < 0 || !Character.isJavaIdentifierPart(source.charAt(at));
    }

    private static final int NO_MATCH = Integer.MIN_VALUE;

    /** The index of the last non-whitespace character at or before {@code from}. */
    private int skipBackWhitespace(int from) {
        int at = from;
        while (at >= 0 && Character.isWhitespace(source.charAt(at))) {
            at--;
        }
        return at;
    }

    /**
     * If {@code word} ends at {@code at} (case-insensitively), the index just before it;
     * {@link #NO_MATCH} otherwise.
     */
    private int matchBack(int at, String word) {
        int start = at - word.length() + 1;
        if (start < 0) {
            return NO_MATCH;
        }
        return source.substring(start, at + 1).equalsIgnoreCase(word) ? start - 1 : NO_MATCH;
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

    private SqlNode parseBind(Directive directive, int commentStart) {
        if (directive.content().trim().startsWith("${")) {
            return parseFilePath(directive);
        }
        // The expression is diagnosed before the dummy is demanded. Studio's SQL builder emits
        // `insert into <t> (/* TODO: columns */)` as an author-fills-this-in placeholder, and the
        // expression is the half worth complaining about (docs/two-way-sql-parser.md decision 4).
        String expr = directive.content().trim();
        if (expr.isEmpty()) {
            throw error("Empty bind expression");
        }
        boolean list = skipWhitespacePeek() == '(';
        SqlNode node = list
                ? new SqlNode.ListBind(expr, ExpressionParser.parse(expr, functions),
                        precededByNotIn(commentStart), directive.sourceLine())
                : new SqlNode.Bind(expr, ExpressionParser.parse(expr, functions),
                        directive.sourceLine());
        skipDummy(list, expr);
        return node;
    }

    /**
     * A file-reference site: {@code ${scope.name}/rel/path} or {@code ${dataset.param}}
     * (docs/duckdb.md). Shape-validated here so a traversal or meta-character never reaches the
     * renderer; the dummy literal that follows is consumed like any bind's.
     */
    private SqlNode parseFilePath(Directive directive) {
        // Shape first, dummy second, for the same reason parseBind reorders: the `${…}` is the
        // half an author gets wrong (docs/two-way-sql-parser.md decision 4).
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
        if (!("scope".equals(channel) || "dataset".equals(channel)
                || "remote".equals(channel)) || !name.matches("[\\p{L}\\p{N}_-]+")) {
            throw error("Unknown file placeholder '" + content + "': only ${scope.<name>},"
                    + " ${dataset.<param>}, and ${remote.<name>} resolve to files");
        }
        if ("dataset".equals(channel) && !suffix.isEmpty()) {
            throw error("A ${dataset.*} placeholder names a whole file; '" + suffix
                    + "' cannot follow it");
        }
        if (!"dataset".equals(channel) && !validScopeSuffix(suffix)) {
            throw error("File placeholder path '" + suffix + "' must be /-separated relative"
                    + " segments of letters, digits, and [._*-] with no '..'");
        }
        skipDummy(false, content);
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
                    || !segment.matches("[\\p{L}\\p{N}._*-]+")) {
                return false;
            }
        }
        return true;
    }

    private SqlNode parseIf(Directive first) {
        List<SqlNode.If.Branch> branches = new ArrayList<>();
        String ifCondition = first.argument("if");
        branches.add(new SqlNode.If.Branch(ExpressionParser.parse(ifCondition, functions),
                ifCondition, first.sourceLine(), parseBlock()));
        // `else` ends the chain. The renderer takes the first branch with no condition and stops,
        // so anything written after the else parses, ships and can never run — and because only
        // an evaluated branch enters the branch denominator, the coverage report cannot tell it
        // from a well-formed chain (docs/two-way-sql-parser.md decision 8).
        boolean sawElse = false;
        while (true) {
            Directive terminator = requireTerminator("if");
            switch (terminator.keyword()) {
                case "elseif" -> {
                    if (sawElse) {
                        throw error("elseif after else: the else branch already matches, so this"
                                + " branch can never run");
                    }
                    String elseifCondition = terminator.argument("elseif");
                    branches.add(new SqlNode.If.Branch(
                            ExpressionParser.parse(elseifCondition, functions),
                            elseifCondition, terminator.sourceLine(), parseBlock()));
                }
                case "else" -> {
                    if (sawElse) {
                        throw error("a second else: the if chain already has one");
                    }
                    sawElse = true;
                    branches.add(new SqlNode.If.Branch(
                            null, null, terminator.sourceLine(), parseBlock()));
                }
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
        // Whitespace of any kind separates the sub-keyword, so a long list expression may wrap
        // before it (docs/two-way-sql-parser.md decision 7). Last match, because the separator is
        // the trailing clause.
        Matcher keyword = SEPARATOR.matcher(listExpr);
        int start = -1;
        int end = -1;
        while (keyword.find()) {
            start = keyword.start();
            end = keyword.end();
        }
        if (start >= 0) {
            String literal = listExpr.substring(end).trim();
            if (literal.length() < 2 || literal.charAt(0) != '\''
                    || literal.charAt(literal.length() - 1) != '\'') {
                throw error("for separator must be a quoted literal, e.g. separator ','");
            }
            separator = literal.substring(1, literal.length() - 1);
            listExpr = listExpr.substring(0, start).trim();
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
        return new SqlNode.For(itemVar, listExpr, ExpressionParser.parse(listExpr, functions),
                separator, first.sourceLine(), body);
    }

    private SqlNode parseScope(Directive directive) {
        // `as boolean` renders the scope as a SELECT-list flag (case when … then 1 else 0 end) for
        // row-level masking, rather than a WHERE predicate (roadmap Phase 29 slice 3). The split
        // lives in ScopeArgument because the linter and the coverage manifest read the same
        // argument and must reach the same answer (docs/two-way-sql-parser.md decision 7).
        ScopeArgument parsed = ScopeArgument.parse(directive.argument("scope"));
        String name = parsed.name();
        String alias = parsed.alias();
        boolean asBoolean = parsed.asBoolean();
        if (name.isEmpty()) {
            throw error("scope directive needs a scope name");
        }
        if (alias != null && !SqlIdentifiers.isIdentifier(alias)) {
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

    /**
     * The optimistic-lock comparison site (docs/edit-conflict.md decision 2). It takes no
     * argument: the column is the route's {@code lock:} declaration, so writing it here too would
     * be a second copy free to disagree. Like the scope directive it replaces a parenthesized
     * dummy predicate, which is what keeps the template runnable in a plain SQL tool.
     */
    private SqlNode parseLock(Directive directive) {
        String argument = directive.argument("lock").trim();
        if (!argument.isEmpty()) {
            throw error("a lock directive takes no argument ('" + argument + "'); the column comes"
                    + " from the route's lock: declaration");
        }
        if (skipWhitespacePeek() != '(') {
            throw error("a lock directive must be followed by a parenthesized dummy predicate, "
                    + "e.g. /*%lock*/ (1=1)");
        }
        skipParenGroup();
        return new SqlNode.Lock(directive.sourceLine());
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

    /**
     * Consumes one complete quoted run into {@code text} — a {@code '...'} string literal, or a
     * {@code "..."} or {@code `...`} quoted identifier. {@code pos} is on the opening delimiter,
     * a doubled delimiter is the only escape, and end of input is an error: the one contract
     * every quote scanner in this parser holds (docs/two-way-sql-parser.md decision 1).
     */
    private void consumeQuotedRun(StringBuilder text, char quote) {
        text.append(consume());
        while (pos < length) {
            char c = consume();
            text.append(c);
            if (c == quote) {
                if (pos < length && source.charAt(pos) == quote) {
                    text.append(consume());
                    continue;
                }
                return;
            }
        }
        throw error(quote == '\''
                ? "Unterminated string literal"
                : "Unterminated quoted identifier");
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

    /**
     * Skips the dummy value a bind site carries so the file runs in a plain SQL tool: a quoted
     * run, or an optional sign and a token, optionally suffixed by a call group, an adjacent
     * quoted run ({@code N'…'}), or a whitespace-separated one after a type keyword
     * ({@code DATE '…'}). One grammar, stated once (docs/two-way-sql-parser.md decision 3).
     *
     * <p>A bind site with no dummy is refused. That is not hygiene: the token scan has no keyword
     * boundary, so {@code select /* a *}{@code /, /* b *}{@code / from t} used to eat {@code from}
     * as the second dummy and render {@code select ?, ? t} — a statement the author never wrote.
     */
    private void skipDummy(boolean list, String site) {
        skipWhitespacePeek();
        if (pos >= length) {
            throw missingDummy(site);
        }
        if (list) {
            skipParenGroup();
            return;
        }
        char c = source.charAt(pos);
        if (c == '\'' || c == '"' || c == '`') {
            skipQuotedRun(c);
            return;
        }
        if (c == '+' || c == '-') {
            consume();
        }
        int tokenStart = pos;
        while (pos < length) {
            char t = source.charAt(pos);
            if (Character.isJavaIdentifierPart(t) || t == '.') {
                consume();
            } else if ((t == '+' || t == '-') && pos > tokenStart
                    && "eE".indexOf(source.charAt(pos - 1)) >= 0) {
                consume(); // the exponent's sign, which is part of the number
            } else {
                break;
            }
        }
        if (pos == tokenStart) {
            // Includes `-- x`: a line comment is not a dummy, and the sign consumed above is not
            // one either.
            throw missingDummy(site);
        }
        String token = source.substring(tokenStart, pos);
        if (pos < length) {
            char next = source.charAt(pos);
            if (next == '(') {
                skipParenGroup(); // a call dummy: now(), coalesce(1, 2)
                return;
            }
            if (next == '\'' || next == '"' || next == '`') {
                skipQuotedRun(next); // a prefixed literal: N'…', X'…', _utf8'…'
                return;
            }
        }
        skipTypedLiteral(token);
    }

    /**
     * A standard typed literal — {@code DATE '2024-01-01'} — is one dummy, but only after one of
     * the type keywords. The whitelist is what keeps {@code select /* x *}{@code / x 'alias'} from
     * losing an alias that some dialects write as a string.
     */
    private void skipTypedLiteral(String token) {
        if (!TYPE_KEYWORDS.contains(token.toLowerCase(Locale.ROOT))) {
            return;
        }
        int after = pos;
        while (after < length && Character.isWhitespace(source.charAt(after))) {
            after++;
        }
        if (after >= length) {
            return;
        }
        char quote = source.charAt(after);
        if (quote != '\'' && quote != '"' && quote != '`') {
            return;
        }
        while (pos < after) {
            consume();
        }
        skipQuotedRun(quote);
    }

    private TqlException missingDummy(String site) {
        return error("a bind site must be followed by a dummy value, e.g. /* " + site + " */ 'x'");
    }

    /**
     * Skips the parenthesized dummy that follows a list bind, a {@code /*%scope*}{@code /} or a
     * {@code /*%lock*}{@code /}. {@code pos} is on the opening {@code (}: every caller checks that
     * with {@link #skipWhitespacePeek()} first.
     *
     * <p>The loop peeks before it consumes, because {@link #skipQuotedRun(char)} expects
     * {@code pos} on the opening quote. Consuming the quote here and handing the run over made
     * that scanner read the <em>next</em> character as the opener, so an empty literal ate its own
     * closing quote and ran to the following quote in the file or to end of input — leaving the
     * group open and returning without a word. A {@code --} remark is skipped for the same reason:
     * the apostrophe in {@code (1, 2 -- don't\n)} is that mis-scan one comment over.
     */
    private void skipParenGroup() {
        int opened = line;
        int groupDepth = 0;
        while (pos < length) {
            char c = source.charAt(pos);
            if (c == '\'' || c == '"' || c == '`') {
                skipQuotedRun(c);
                continue;
            }
            if (c == '-' && pos + 1 < length && source.charAt(pos + 1) == '-') {
                while (pos < length && source.charAt(pos) != '\n') {
                    consume();
                }
                continue;
            }
            consume();
            if (c == '(') {
                groupDepth++;
            } else if (c == ')' && --groupDepth == 0) {
                return;
            }
        }
        throw error("Unterminated dummy value group opened on line " + opened);
    }

    /**
     * Skips one quoted run at the dummy layer. The same contract the statement layer's
     * {@link #consumeQuotedRun} holds — {@code pos} is on the opening delimiter, a doubled
     * delimiter is the only escape, end of input is an error — with the run discarded rather than
     * appended, which is why there are two methods and not one overloaded name
     * (docs/two-way-sql-parser.md decisions 1 and 2).
     */
    private void skipQuotedRun(char quote) {
        consume();
        while (pos < length) {
            if (consume() == quote) {
                if (pos < length && source.charAt(pos) == quote) {
                    consume();
                    continue;
                }
                return;
            }
        }
        throw error("Unterminated dummy value");
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
        /**
         * The keyword ends at the first whitespace, not at the first space. A long guard written
         * across lines — {@code /*%if\n  q != null\n*}{@code /} — used to be reported as
         * {@code Unknown directive 'if\n'}, naming a directive that does not exist, for the
         * natural way to write it (docs/two-way-sql-parser.md decision 7).
         */
        String keyword() {
            for (int i = 0; i < content.length(); i++) {
                if (Character.isWhitespace(content.charAt(i))) {
                    return content.substring(0, i);
                }
            }
            return content;
        }

        String argument(String keyword) {
            return content.substring(keyword.length()).trim();
        }
    }
}
