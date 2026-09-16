package io.tesseraql.core.sql;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds a transaction-control statement in a 2-way SQL file — {@code COMMIT}, {@code ROLLBACK},
 * {@code BEGIN}/{@code START TRANSACTION}, {@code SET TRANSACTION} — at the start of a statement,
 * outside comments, string literals, quoted identifiers and dollar-quoted bodies.
 *
 * <p>A 2-way SQL file never owns its transaction: the command pipeline does on a request, the
 * declarative test runner does for a case (and always rolls it back), the Studio sandbox does for
 * a console run. A {@code commit;} inside the file — the trailing terminator of a DBA script, an
 * Oracle habit — ended the transaction the runner thought it owned, the write persisted, and every
 * later case and every later run saw one more row, while the documented guarantee said a test run
 * never commits anything (docs/audit-low-leads.md G17). The parser refuses the file, so lint, the
 * runner, the compiler and the sandbox all answer with one code.
 *
 * <p>Only a statement's first word is judged, so a PL/SQL or T-SQL block that starts with
 * {@code BEGIN} and continues with a statement is not a transaction start; {@code BEGIN} alone,
 * {@code BEGIN WORK}, {@code BEGIN TRANSACTION} and {@code BEGIN TRAN} are.
 */
public final class TransactionControl {

    /** TQL-SQL-2123: a 2-way SQL file carries a transaction-control statement. */
    public static final TqlErrorCode IN_A_FILE = new TqlErrorCode(TqlDomain.SQL, 2123);

    /** The statement found, as the author spelled its keywords, and the 1-based line it starts on. */
    public record Found(String statement, int line) {
    }

    private TransactionControl() {
    }

    /** The first transaction-control statement in {@code sql}, if any. */
    public static Optional<Found> find(String sql) {
        Scanner scanner = new Scanner(sql);
        while (true) {
            int start = scanner.statementStart();
            if (start < 0) {
                return Optional.empty();
            }
            String first = scanner.word(start);
            String second = scanner.word(scanner.afterWord(start));
            String statement = judge(first, second,
                    scanner.endsStatement(scanner.afterWord(start)));
            if (statement != null) {
                return Optional.of(new Found(statement, scanner.lineOf(start)));
            }
            if (!scanner.skipStatement()) {
                return Optional.empty();
            }
        }
    }

    /** The refusal every surface raises for a file that carries one. */
    public static TqlException refuse(Found found) {
        return TqlException.builder(IN_A_FILE)
                .message("The file carries a transaction-control statement on line " + found.line()
                        + " (" + found.statement() + "). A 2-way SQL file never owns its"
                        + " transaction: the command pipeline does on a request and the test runner"
                        + " does for a case, which it always rolls back — remove the statement")
                .line(found.line())
                .build();
    }

    private static String judge(String first, String second, boolean firstEndsStatement) {
        String upper = first.toUpperCase(Locale.ROOT);
        String next = second.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "COMMIT", "ROLLBACK" -> first;
            case "START", "SET" -> "TRANSACTION".equals(next) ? first + " " + second : null;
            case "BEGIN" -> firstEndsStatement || "WORK".equals(next) || "TRANSACTION".equals(next)
                    || "TRAN".equals(next)
                            ? (second.isEmpty() ? first : first + " " + second)
                            : null;
            default -> null;
        };
    }

    /** A cursor over the file that knows what is not a statement: comments, literals, quoting. */
    private static final class Scanner {
        private final String sql;
        private int pos;

        Scanner(String sql) {
            this.sql = sql;
        }

        /** The index of the next statement's first word, or -1 at the end of the file. */
        int statementStart() {
            skipBlank(true);
            return pos < sql.length() ? pos : -1;
        }

        /** Advances past the current statement's terminator; false at the end of the file. */
        boolean skipStatement() {
            while (pos < sql.length()) {
                char c = sql.charAt(pos);
                if (c == ';') {
                    pos++;
                    return true;
                }
                if (!skipOpaque()) {
                    pos++;
                }
            }
            return false;
        }

        /** The identifier-shaped word at {@code at}, or an empty string. */
        String word(int at) {
            if (at < 0 || at >= sql.length()) {
                return "";
            }
            int end = at;
            while (end < sql.length() && (Character.isLetter(sql.charAt(end))
                    || sql.charAt(end) == '_')) {
                end++;
            }
            return sql.substring(at, end);
        }

        /** The index of the first word after the one at {@code at}, past blanks and comments. */
        int afterWord(int at) {
            int saved = pos;
            pos = at + word(at).length();
            skipBlank(false);
            int next = pos;
            pos = saved;
            return next;
        }

        /** Whether the statement ends at {@code at}: a terminator or the end of the file. */
        boolean endsStatement(int at) {
            return at >= sql.length() || sql.charAt(at) == ';';
        }

        int lineOf(int at) {
            int line = 1;
            for (int i = 0; i < at && i < sql.length(); i++) {
                if (sql.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        }

        /** Skips whitespace and comments; with {@code terminators}, empty statements too. */
        private void skipBlank(boolean terminators) {
            while (pos < sql.length()) {
                char c = sql.charAt(pos);
                if (Character.isWhitespace(c) || (terminators && c == ';')) {
                    pos++;
                } else if (c == '-' && peek(1) == '-') {
                    skipLineComment();
                } else if (c == '/' && peek(1) == '*') {
                    skipBlockComment();
                } else {
                    return;
                }
            }
        }

        /** Skips one opaque run at {@code pos} — a comment, a literal, a quoted run; false if none. */
        private boolean skipOpaque() {
            char c = sql.charAt(pos);
            if (c == '-' && peek(1) == '-') {
                skipLineComment();
                return true;
            }
            if (c == '/' && peek(1) == '*') {
                skipBlockComment();
                return true;
            }
            if (c == '\'' || c == '"' || c == '`') {
                skipQuoted(c);
                return true;
            }
            if (c == '$') {
                return skipDollarQuoted();
            }
            return false;
        }

        private void skipLineComment() {
            while (pos < sql.length() && sql.charAt(pos) != '\n') {
                pos++;
            }
        }

        private void skipBlockComment() {
            int end = sql.indexOf("*/", pos + 2);
            pos = end < 0 ? sql.length() : end + 2;
        }

        /** A quoted run; a doubled delimiter is the escape, an unterminated run reaches the end. */
        private void skipQuoted(char quote) {
            pos++;
            while (pos < sql.length()) {
                if (sql.charAt(pos) == quote) {
                    if (peek(1) == quote) {
                        pos += 2;
                        continue;
                    }
                    pos++;
                    return;
                }
                pos++;
            }
        }

        /** A PostgreSQL dollar-quoted body ({@code $$…$$}, {@code $tag$…$tag$}); false if not one. */
        private boolean skipDollarQuoted() {
            int end = pos + 1;
            while (end < sql.length() && (Character.isLetterOrDigit(sql.charAt(end))
                    || sql.charAt(end) == '_')) {
                end++;
            }
            if (end >= sql.length() || sql.charAt(end) != '$'
                    || (end > pos + 1 && Character.isDigit(sql.charAt(pos + 1)))) {
                return false;
            }
            String tag = sql.substring(pos, end + 1);
            int close = sql.indexOf(tag, end + 1);
            pos = close < 0 ? sql.length() : close + tag.length();
            return true;
        }

        private char peek(int ahead) {
            return pos + ahead < sql.length() ? sql.charAt(pos + ahead) : '\0';
        }
    }
}
