package io.tesseraql.core.http;

import java.text.Normalizer;
import java.util.Objects;

/**
 * The one filename sanitizer for {@code Content-Disposition} headers, and the one writer of
 * the whole value.
 *
 * <p>Four call sites carried their own regex and disagreed: three stripped CR/LF and the double
 * quote but let a backslash through — {@code report.pdf\} escapes the closing quote and leaves
 * the quoted-string unterminated, which download parsers resolve differently (filename spoofing
 * and extension confusion, reachable from a client-supplied upload filename) — and the SQL
 * export's writer sanitized nothing at all. One helper, the strictest of the four, so the next
 * fix lands once.
 *
 * <p>The value is RFC 6266's. A name US-ASCII can spell goes out exactly as it always did,
 * {@code attachment; filename="orders.csv"}. A name it cannot goes out in both forms the RFC's
 * appendix D asks a sender for: a {@code filename} fallback that is pure ASCII, first, for the
 * recipients that read only that parameter, then {@code filename*} carrying the name itself as
 * RFC 8187 {@code UTF-8''} percent-encoded octets. Before either, the name is folded: the
 * characters that break a quoted-string, the controls a field value may not carry, and the
 * invisible format characters that make one name read as another. Letters in any script pass
 * through untouched — the fold is not where a Japanese name becomes ASCII; the fallback is,
 * and only for the fallback's own half.
 */
public final class ContentDisposition {

    private ContentDisposition() {
    }

    /**
     * {@code filename} with every character that breaks a quoted-string, every control and
     * every invisible format character replaced by {@code _}, one per code point; a name a
     * directory entry cannot carry — blank, {@code .} or {@code ..}, exactly those — becomes
     * {@code _}, so {@code report..csv} is still a name. Every other character, in any script,
     * is kept — the {@code filename*} half needs the name itself.
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null) {
            return null;
        }
        if (unnameable(filename)) {
            return "_";
        }
        StringBuilder out = new StringBuilder(filename.length());
        filename.codePoints().forEach(cp -> out.appendCodePoint(folds(cp) ? '_' : cp));
        return out.toString();
    }

    /**
     * The whole {@code attachment; …} value. A name US-ASCII spells: today's exact value. A
     * name it does not: {@code filename="<ascii fallback>"; filename*=UTF-8''<octets>}, both
     * halves built from the same sanitized name. A null name is a programming error: every
     * caller derives one before it gets here.
     */
    public static String attachment(String filename) {
        String name = sanitizeFilename(Objects.requireNonNull(filename, "filename"));
        if (name.chars().allMatch(c -> c < 0x80)) {
            return "attachment; filename=\"" + name + "\"";
        }
        return "attachment; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''"
                + PercentEncoding.extValue(name);
    }

    /**
     * The {@code filename} half of a non-ASCII name, per code point of the name: ASCII stays; a
     * combining mark adds nothing (it rides on the character before it, which is already in the
     * fallback — so a name a macOS client spells decomposed falls back the same as its composed
     * twin); any other character whose canonical decomposition is an ASCII letter or digit
     * followed only by marks becomes that letter or digit; every other character becomes one
     * {@code _}. So {@code café.csv} is {@code cafe.csv}, {@code Übersicht.csv} is
     * {@code Ubersicht.csv}, {@code 受注一覧.csv} is {@code ____.csv} — RFC 6266 appendix D's
     * "substituting characters with US-ASCII sequences", the locale-free part of it. Never
     * {@code ?}: that is the corruption a reader would be looking at, not a fallback. What the
     * sanitizer refuses as a name, the fallback refuses too.
     */
    private static String asciiFallback(String name) {
        StringBuilder out = new StringBuilder(name.length());
        name.codePoints().forEach(cp -> {
            if (cp < 0x80) {
                out.append((char) cp);
            } else if (isMark(cp)) {
                if (out.isEmpty()) {
                    out.append('_');
                }
            } else {
                out.append(asciiBase(cp));
            }
        });
        String fallback = out.toString();
        return unnameable(fallback) ? "_" : fallback;
    }

    /** The ASCII letter or digit a code point decomposes to under its marks, else {@code _}. */
    private static char asciiBase(int cp) {
        String decomposed = Normalizer.normalize(Character.toString(cp), Normalizer.Form.NFD);
        int base = decomposed.codePointAt(0);
        boolean letterUnderMarks = base < 0x80 && Character.isLetterOrDigit(base)
                && decomposed.codePoints().skip(1).allMatch(ContentDisposition::isMark);
        return letterUnderMarks ? (char) base : '_';
    }

    /** The names a directory entry cannot carry: blank, {@code .} and {@code ..} — exactly. */
    private static boolean unnameable(String name) {
        return name.isBlank() || ".".equals(name) || "..".equals(name);
    }

    private static boolean isMark(int cp) {
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * What the fold replaces: the quoted-string breakers ({@code "} and {@code \}); every
     * Unicode control ({@code Cc}: C0 and DEL, which RFC 9110 section 5.5 declares invalid in a
     * field value, and C1, which a legacy reader of {@code filename=} decodes as Latin-1
     * controls); the line and paragraph separators; and every Unicode format character
     * ({@code Cf}: the twelve bidirectional controls that make {@code inv<U+202E>gpj.exe} read
     * as {@code invexe.jpg}, the zero-width space, the byte-order mark and word joiner, the
     * interlinear and tag characters) — every one of them but the two joiners U+200C and
     * U+200D, which are letters' glue in Persian, in Indic conjuncts and in emoji sequences,
     * not disguises (IDNA2008 admits exactly those two of the format characters).
     */
    private static boolean folds(int cp) {
        if (cp == '"' || cp == '\\') {
            return true;
        }
        if (cp == 0x200C || cp == 0x200D) {
            return false;
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
    }
}
