package io.tesseraql.core.sql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A dependency-free, lenient extractor of the tables a SQL statement reads from and writes to, used
 * by the documentation portal to cross-link routes and schema tables (Studio backlog: the SQL&rarr;
 * table dependency graph). It is a <em>lexical heuristic</em>, not a full SQL grammar: it scans the
 * statement for the table names that follow the table-introducing keywords ({@code FROM}/{@code JOIN}
 * /{@code USING} for reads, {@code INSERT INTO}/{@code UPDATE}/{@code DELETE FROM}/{@code MERGE INTO}
 * for writes), skipping comments, string literals, common-table-expression names, and derived-table
 * subqueries. It deliberately accepts ambiguity over a heavyweight parser — the result is for
 * navigation and documentation, so a missed exotic construct degrades to a missing cross-link, never
 * a wrong execution.
 *
 * <p>The input is TesseraQL 2-way SQL: the {@code /* … *}{@code /} directive comments (binds,
 * {@code if}/{@code for}/{@code scope}) are stripped along with ordinary {@code --} and
 * {@code /* … *}{@code /} comments, so directives never masquerade as tables. Schema/catalog
 * qualifiers are dropped to the bare table name (so {@code public.users} and {@code users} unify) and
 * identifier quotes are removed, so a name matches the catalog the {@code schema} goal introspects.
 */
public final class SqlTableReferences {

    /** How a statement touches a table: it reads rows from it, or it writes rows to it. */
    public enum Access {
        READ, WRITE
    }

    /**
     * One referenced table and how the statement uses it.
     *
     * @param table  the bare (unqualified, unquoted) table name, as written
     * @param access whether the statement reads from or writes to the table
     */
    public record TableRef(String table, Access access) {
    }

    /**
     * Reserved structural keywords: they terminate a table reference or alias and are never reported
     * as table names. Kept conservative — a non-reserved word that is commonly an unquoted table name
     * (say {@code target} or {@code source}) is intentionally absent, so it is not mistaken for a
     * keyword and dropped. (A truly reserved word used as a table name must be quoted, and a quoted
     * identifier is never matched against this set.)
     */
    private static final Set<String> KEYWORDS = Set.of("SELECT", "FROM", "WHERE", "JOIN", "INNER",
            "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL", "LATERAL", "ON", "USING", "GROUP",
            "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "FETCH", "ONLY", "UNION", "INTERSECT",
            "EXCEPT", "ALL", "AS", "INTO", "INSERT", "UPDATE", "DELETE", "SET", "VALUES",
            "RETURNING",
            "WITH", "RECURSIVE", "MERGE", "REPLACE", "AND", "OR", "NOT", "IN", "IS", "NULL",
            "EXISTS",
            "BETWEEN", "LIKE", "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "DEFAULT", "FOR",
            "KEY");

    private SqlTableReferences() {
    }

    /**
     * The distinct tables the statement references, sorted by name (case-insensitively) then access.
     * An empty list when no table reference is recognized (including a {@code null}/blank input or a
     * contract/service binding with no SQL text).
     */
    public static List<TableRef> extract(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<Tok> tokens = tokenize(sql);
        Set<String> cteNames = commonTableExpressions(tokens);
        LinkedHashSet<TableRef> refs = new LinkedHashSet<>();
        boolean deletePending = false;
        String prevKeyword = null;
        for (int i = 0; i < tokens.size(); i++) {
            Tok tok = tokens.get(i);
            if (tok.type == T.SEMI) {
                deletePending = false;
                prevKeyword = null;
                continue;
            }
            if (tok.type != T.WORD || !tok.keyword) {
                continue;
            }
            String keyword = tok.text.toUpperCase(Locale.ROOT);
            switch (keyword) {
                case "DELETE" -> deletePending = true;
                case "FROM" -> {
                    i = captureTables(tokens, i + 1, deletePending ? Access.WRITE : Access.READ,
                            true, cteNames, refs);
                    deletePending = false;
                }
                case "JOIN" -> i = captureTables(tokens, i + 1, Access.READ, false, cteNames, refs);
                case "USING" ->
                    i = captureTables(tokens, i + 1, Access.READ, false, cteNames, refs);
                case "INTO" ->
                    i = captureTables(tokens, i + 1, Access.WRITE, false, cteNames, refs);
                case "UPDATE" -> {
                    // A statement-leading UPDATE writes its target; the UPDATE of a `FOR UPDATE`
                    // lock or an `ON … KEY UPDATE` upsert action does not — suppress those.
                    if (!"FOR".equals(prevKeyword) && !"KEY".equals(prevKeyword)) {
                        i = captureTables(tokens, i + 1, Access.WRITE, true, cteNames, refs);
                    }
                }
                default -> {
                    // Any other keyword cannot introduce a table; leave deletePending for FROM.
                }
            }
            prevKeyword = keyword;
        }
        List<TableRef> out = new ArrayList<>(refs);
        out.sort(Comparator.comparing((TableRef r) -> r.table().toLowerCase(Locale.ROOT))
                .thenComparing(r -> r.access().name()));
        return List.copyOf(out);
    }

    /**
     * Captures the table(s) introduced at {@code start}, returning the index of the last consumed
     * token (so the caller's loop resumes after them). A parenthesized subquery is skipped (its
     * inner {@code FROM} is scanned on its own), a CTE name is excluded, and an optional
     * {@code [AS] alias} is consumed. When {@code commaSeparated} (a {@code FROM} or {@code UPDATE}
     * list), a comma at the same nesting continues to the next table.
     */
    private static int captureTables(List<Tok> tokens, int start, Access access,
            boolean commaSeparated, Set<String> cteNames, Set<TableRef> refs) {
        int i = start;
        while (i < tokens.size()) {
            Tok tok = tokens.get(i);
            if (tok.type == T.WORD && tok.keyword && tok.text.equalsIgnoreCase("ONLY")) {
                i++; // FROM ONLY <table> (Postgres inheritance) — skip the modifier.
                continue;
            }
            if (tok.type != T.WORD || tok.keyword) {
                return i - 1; // A subquery '(', a clause keyword, or punctuation ends the list.
            }
            // Read a possibly schema-qualified name; keep only the final (table) segment.
            String name = tok.text;
            int j = i;
            while (j + 2 < tokens.size() && tokens.get(j + 1).type == T.DOT
                    && tokens.get(j + 2).type == T.WORD) {
                name = tokens.get(j + 2).text;
                j += 2;
            }
            if (!cteNames.contains(name.toLowerCase(Locale.ROOT))) {
                refs.add(new TableRef(name, access));
            }
            i = j + 1;
            i = skipAlias(tokens, i);
            if (commaSeparated && i < tokens.size() && tokens.get(i).type == T.COMMA) {
                i++; // Continue to the next table in the FROM/UPDATE list.
                continue;
            }
            return i - 1;
        }
        return i - 1;
    }

    /** Consumes an optional table alias ({@code AS name} or a bare {@code name}), returning the next index. */
    private static int skipAlias(List<Tok> tokens, int i) {
        if (i >= tokens.size()) {
            return i;
        }
        Tok tok = tokens.get(i);
        if (tok.type == T.WORD && tok.keyword && tok.text.equalsIgnoreCase("AS")) {
            i++;
            if (i < tokens.size() && tokens.get(i).type == T.WORD) {
                i++;
            }
            return i;
        }
        if (tok.type == T.WORD && !tok.keyword) {
            return i + 1; // A bare alias (FROM users u).
        }
        return i;
    }

    /**
     * The names declared by {@code WITH … AS ( … )} common-table expressions: any identifier
     * immediately followed by {@code AS} then {@code (}. They are query-local names, not catalog
     * tables, so a {@code FROM <cte>} must not be reported as a dependency.
     */
    private static Set<String> commonTableExpressions(List<Tok> tokens) {
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i + 2 < tokens.size(); i++) {
            Tok name = tokens.get(i);
            Tok as = tokens.get(i + 1);
            Tok open = tokens.get(i + 2);
            if (name.type == T.WORD && !name.keyword && as.type == T.WORD
                    && as.text.equalsIgnoreCase("AS") && open.type == T.LPAREN) {
                names.add(name.text.toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private enum T {
        WORD, DOT, COMMA, LPAREN, RPAREN, SEMI, OTHER
    }

    private record Tok(T type, String text, boolean keyword) {
    }

    /**
     * Lexes the SQL into the tokens the scanner needs — identifiers (quoted or bare), the structural
     * punctuation {@code . , ( ) ;} — dropping whitespace, numbers, string literals, and every kind
     * of comment (including the 2-way {@code /* … *}{@code /} directives).
     */
    private static List<Tok> tokenize(String sql) {
        List<Tok> tokens = new ArrayList<>();
        int n = sql.length();
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '\'') {
                i = skipQuoted(sql, i + 1, '\'');
            } else if (c == '"' || c == '`') {
                int start = i + 1;
                int end = skipQuoted(sql, start, c);
                tokens.add(new Tok(T.WORD, sql.substring(start, end - 1), false));
                i = end;
            } else if (c == '[') {
                int start = i + 1;
                int end = start;
                while (end < n && sql.charAt(end) != ']') {
                    end++;
                }
                tokens.add(new Tok(T.WORD, sql.substring(start, end), false));
                i = Math.min(n, end + 1);
            } else if (isIdentifierStart(c)) {
                int start = i;
                while (i < n && isIdentifierPart(sql.charAt(i))) {
                    i++;
                }
                String word = sql.substring(start, i);
                tokens.add(new Tok(T.WORD, word, KEYWORDS.contains(word.toUpperCase(Locale.ROOT))));
            } else if (Character.isDigit(c)) {
                while (i < n
                        && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '.')) {
                    i++;
                }
            } else {
                tokens.add(new Tok(punctuation(c), String.valueOf(c), false));
                i++;
            }
        }
        return tokens;
    }

    /** Skips a quoted run that started just after {@code start}, honoring a doubled quote escape. */
    private static int skipQuoted(String sql, int start, char quote) {
        int n = sql.length();
        int i = start;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2; // Escaped quote ('' or "").
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return n;
    }

    private static T punctuation(char c) {
        return switch (c) {
            case '.' -> T.DOT;
            case ',' -> T.COMMA;
            case '(' -> T.LPAREN;
            case ')' -> T.RPAREN;
            case ';' -> T.SEMI;
            default -> T.OTHER;
        };
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }
}
