package io.tesseraql.core.http;

/**
 * The one filename sanitizer for {@code Content-Disposition} headers.
 *
 * <p>Four call sites carried their own regex and disagreed: three stripped CR/LF and the double
 * quote but let a backslash through — {@code report.pdf\} escapes the closing quote and leaves
 * the quoted-string unterminated, which download parsers resolve differently (filename spoofing
 * and extension confusion, reachable from a client-supplied upload filename) — and the SQL
 * export's writer sanitized nothing at all. One helper, the strictest of the four, so the next
 * fix lands once.
 */
public final class ContentDisposition {

    private ContentDisposition() {
    }

    /** {@code filename} with every character that breaks a quoted-string replaced by {@code _}. */
    public static String sanitizeFilename(String filename) {
        return filename == null ? null : filename.replaceAll("[\\\\\"\\r\\n]", "_");
    }

    /** The whole {@code attachment; filename="…"} value, sanitized. */
    public static String attachment(String filename) {
        return "attachment; filename=\"" + sanitizeFilename(filename) + "\"";
    }
}
