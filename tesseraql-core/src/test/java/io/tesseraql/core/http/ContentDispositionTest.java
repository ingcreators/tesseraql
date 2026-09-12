package io.tesseraql.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one filename sanitizer and the one writer of the {@code Content-Disposition} value
 * (docs/download-name-and-bytes.md).
 *
 * <p>The backslash case is the one three of the four former call sites missed: {@code
 * report.pdf\} escaped the closing quote and left the quoted-string unterminated, which download
 * parsers resolve differently — reachable from a client-supplied upload filename on the
 * attachment surface. The rest is the RFC 6266 two-parameter form for a name US-ASCII cannot
 * spell, asserted as exact whole values: every fixture below pins a code-point class that a
 * built one-edit variant of the helper gets wrong, and a sampled range is walked whole, because
 * a fixture pins only the code points it carries.
 *
 * <p>Control and format fixtures are spelled as {@code \\uXXXX} escapes so no editor can drop an
 * invisible byte; CR, LF, {@code "} and {@code \\} are never spelled that way (the Java lexer
 * expands a {@code \\u} escape before it reads the literal).
 */
class ContentDispositionTest {

    @Test
    void everyQuotedStringBreakerIsReplaced() {
        assertThat(ContentDisposition.sanitizeFilename("report.pdf\\"))
                .isEqualTo("report.pdf_");
        assertThat(ContentDisposition.sanitizeFilename("a\"b\r\nc"))
                .isEqualTo("a_b__c");
    }

    /**
     * The sanitizer is Unicode-transparent by decision (docs/download-name-and-bytes.md, decision 8):
     * every letter of every script, and the two joiners that Persian, Indic conjuncts and emoji
     * sequences are spelled with, pass through, because {@code filename*} needs the name itself.
     * This is a contract about the sanitizer, never coverage of the wire — a Japanese name that
     * passes through here still had to be spelled for the header, which the {@code attachment()}
     * rows below pin.
     */
    @Test
    void theSanitizerIsTransparentToEveryScript() {
        for (String name : List.of("月次レポート.csv", "café (1).csv", "受注 100%'s.csv",
                "می\u200Cخواهم.csv",
                "👨\u200D👩\u200D👧.csv",
                "क\u094D\u200Dष.csv")) {
            assertThat(ContentDisposition.sanitizeFilename(name)).as(name).isEqualTo(name);
        }
    }

    @Test
    void theAttachmentValueIsWholeAndQuoted() {
        assertThat(ContentDisposition.attachment("orders\".csv"))
                .isEqualTo("attachment; filename=\"orders_.csv\"");
    }

    /** The ASCII branch is byte-identical to what every client already handles. */
    @Test
    void anAsciiNameKeepsTheValueItAlwaysHad() {
        for (String name : List.of("orders.csv", "report..csv", "100%.csv", "a;b=c, d.csv",
                ".hidden", "caf%C3%A9.csv", "~|:*?<>.csv")) {
            assertThat(ContentDisposition.attachment(name)).as(name)
                    .isEqualTo("attachment; filename=\"" + name + "\"");
        }
    }

    /** PollLoop's rule: exact, never a substring — {@code report..csv} is still a name. */
    @Test
    void aNameADirectoryEntryCannotCarryBecomesAnUnderscore() {
        for (String name : List.of(".", "..", "", "   ", "\u3000")) {
            assertThat(ContentDisposition.sanitizeFilename(name)).as("[" + name + "]")
                    .isEqualTo("_");
            assertThat(ContentDisposition.attachment(name)).as("[" + name + "]")
                    .isEqualTo("attachment; filename=\"_\"");
        }
        assertThat(ContentDisposition.sanitizeFilename("report..csv")).isEqualTo("report..csv");
        assertThat(ContentDisposition.sanitizeFilename("..csv")).isEqualTo("..csv");
        assertThat(ContentDisposition.sanitizeFilename(" ..")).isEqualTo(" ..");
    }

    @Test
    void aLatin1NameCarriesAnAsciiFallbackFirstAndTheNameItselfSecond() {
        assertThat(ContentDisposition.attachment("café.csv"))
                .isEqualTo("attachment; filename=\"cafe.csv\"; filename*=UTF-8''caf%C3%A9.csv");
        assertThat(ContentDisposition.attachment("Übersicht.csv")).isEqualTo(
                "attachment; filename=\"Ubersicht.csv\"; filename*=UTF-8''%C3%9Cbersicht.csv");
        assertThat(ContentDisposition.attachment("ÿ.csv"))
                .isEqualTo("attachment; filename=\"y.csv\"; filename*=UTF-8''%C3%BF.csv");
    }

    /**
     * A macOS client spells {@code café} as {@code c a f e U+0301}; nothing on the inbound path
     * normalizes it. The fallback is the same for both spellings; {@code filename*} keeps the
     * octets as stored.
     */
    @Test
    void aDecomposedSpellingHasTheSameFallbackAsTheComposedOne() {
        assertThat(ContentDisposition.attachment("cafe\u0301.csv"))
                .isEqualTo("attachment; filename=\"cafe.csv\"; filename*=UTF-8''cafe%CC%81.csv");
        assertThat(ContentDisposition.attachment("U\u0308bersicht.csv")).isEqualTo(
                "attachment; filename=\"Ubersicht.csv\"; filename*=UTF-8''U%CC%88bersicht.csv");
        assertThat(ContentDisposition.attachment("Vie\u0323\u0302t.csv")).isEqualTo(
                "attachment; filename=\"Viet.csv\"; filename*=UTF-8''Vie%CC%A3%CC%82t.csv");
        assertThat(ContentDisposition.attachment("か\u3099.csv"))
                .isEqualTo(
                        "attachment; filename=\"_.csv\"; filename*=UTF-8''%E3%81%8B%E3%82%99.csv");
    }

    /**
     * A Latin-1 letter with no canonical decomposition — {@code ø ß æ ð þ} — is an underscore,
     * never the raw byte a legacy reader would have mis-decoded; the {@code é} rows above pin
     * the gate and cannot pin this, because they decompose to ASCII.
     */
    @Test
    void aLetterWithoutADecompositionFallsBackToAnUnderscoreNotARawByte() {
        assertThat(ContentDisposition.attachment("Bjørn.csv"))
                .isEqualTo("attachment; filename=\"Bj_rn.csv\"; filename*=UTF-8''Bj%C3%B8rn.csv");
        assertThat(ContentDisposition.attachment("Straße.csv")).isEqualTo(
                "attachment; filename=\"Stra_e.csv\"; filename*=UTF-8''Stra%C3%9Fe.csv");
    }

    /** Over the whole repertoire: the fallback half is printable ASCII, one token, no breaker. */
    @Test
    void theFallbackHalfIsPrintableAsciiForEveryCodePoint() {
        for (int cp = 0x80; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            String value = ContentDisposition.attachment("a" + Character.toString(cp) + "b.csv");
            String rest = value.substring("attachment; filename=\"".length());
            int close = rest.contains("\"; filename*=UTF-8''")
                    ? rest.indexOf("\"; filename*=UTF-8''")
                    : rest.length() - 1;
            assertThat(rest.charAt(close)).as("U+%04X: %s", cp, value).isEqualTo('"');
            String fallback = rest.substring(0, close);
            for (int i = 0; i < fallback.length(); i++) {
                char c = fallback.charAt(i);
                assertThat(c >= 0x20 && c < 0x7F && c != '"' && c != '\\')
                        .as("U+%04X falls back to %s", cp, fallback).isTrue();
            }
        }
    }

    /**
     * Five code points decompose canonically to ASCII punctuation ({@code ; < = >} and a
     * backtick); a base that is not a letter or digit is not a fallback the name had.
     */
    @Test
    void theFallbackNeverIntroducesPunctuationTheNameDidNotHave() {
        for (Map.Entry<String, String> row : Map.of(
                "a\u037Eb.csv", "a%CD%BEb.csv", "a\u226Eb.csv", "a%E2%89%AEb.csv",
                "a\u2260b.csv", "a%E2%89%A0b.csv", "a\u226Fb.csv", "a%E2%89%AFb.csv",
                "a\u1FEFb.csv", "a%E1%BF%AFb.csv").entrySet()) {
            assertThat(ContentDisposition.attachment(row.getKey())).as(row.getValue())
                    .isEqualTo("attachment; filename=\"a_b.csv\"; filename*=UTF-8''"
                            + row.getValue());
        }
        assertThat(ContentDisposition.attachment("\u212Aelvin.csv"))
                .isEqualTo(
                        "attachment; filename=\"Kelvin.csv\"; filename*=UTF-8''%E2%84%AAelvin.csv");
    }

    /** NFD, not NFKD: a ligature or a circled digit is one character, so one underscore. */
    @Test
    void theFallbackDecomposesCanonicallyNotByCompatibility() {
        assertThat(ContentDisposition.attachment("ﬁle.csv"))
                .isEqualTo("attachment; filename=\"_le.csv\"; filename*=UTF-8''%EF%AC%81le.csv");
        assertThat(ContentDisposition.attachment("①Ａ.csv"))
                .isEqualTo(
                        "attachment; filename=\"__.csv\"; filename*=UTF-8''%E2%91%A0%EF%BC%A1.csv");
    }

    /** One underscore per code point of the name; a mark rides on the character before it. */
    @Test
    void aNonLatinNameFallsBackToOneUnderscorePerCodePoint() {
        assertThat(ContentDisposition.attachment("受注一覧.csv")).isEqualTo(
                "attachment; filename=\"____.csv\";"
                        + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7.csv");
        assertThat(ContentDisposition.attachment("한글.csv"))
                .isEqualTo(
                        "attachment; filename=\"__.csv\"; filename*=UTF-8''%ED%95%9C%EA%B8%80.csv");
        assertThat(ContentDisposition.attachment("न\u093Eम.csv")).isEqualTo(
                "attachment; filename=\"__.csv\"; filename*=UTF-8''%E0%A4%A8%E0%A4%BE%E0%A4%AE.csv");
    }

    @Test
    void anAstralCharacterIsOneCodePointOnBothHalves() {
        assertThat(ContentDisposition.attachment("a😀b.csv"))
                .isEqualTo("attachment; filename=\"a_b.csv\"; filename*=UTF-8''a%F0%9F%98%80b.csv");
    }

    @Test
    void aLoneSurrogateIsTheReplacementCharacterNotAQuestionMark() {
        assertThat(ContentDisposition.attachment("a\uD83Db.csv"))
                .isEqualTo("attachment; filename=\"a_b.csv\"; filename*=UTF-8''a%EF%BF%BDb.csv");
        assertThat(ContentDisposition.attachment("a\uDE00b.csv"))
                .isEqualTo("attachment; filename=\"a_b.csv\"; filename*=UTF-8''a%EF%BF%BDb.csv");
    }

    /**
     * The sanitizer's exact rule runs on the name; the fallback re-applies it to its own result,
     * because dropping a mark can leave exactly the name a directory entry cannot carry.
     */
    @Test
    void theFallbackNeverNamesADirectoryEntry() {
        assertThat(ContentDisposition.attachment("..\u0301"))
                .isEqualTo("attachment; filename=\"_\"; filename*=UTF-8''..%CC%81");
        assertThat(ContentDisposition.attachment(".\u0301"))
                .isEqualTo("attachment; filename=\"_\"; filename*=UTF-8''.%CC%81");
        assertThat(ContentDisposition.attachment("\u0301\u0302"))
                .isEqualTo("attachment; filename=\"_\"; filename*=UTF-8''%CC%81%CC%82");
        assertThat(ContentDisposition.attachment("\u0301.csv"))
                .isEqualTo("attachment; filename=\"_.csv\"; filename*=UTF-8''%CC%81.csv");
        assertThat(ContentDisposition.attachment(" \u0301"))
                .isEqualTo("attachment; filename=\"_\"; filename*=UTF-8''%20%CC%81");
    }

    /**
     * The fold, walked whole rather than sampled: every control ({@code Cc}), every format
     * character ({@code Cf}) but the two joiners, the line and paragraph separators, and the two
     * quoted-string breakers fold to one underscore; every other code point passes through.
     */
    @Test
    void everyControlAndFormatCharacterIsFoldedAndNothingElseIs() {
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            if (cp >= 0xD800 && cp <= 0xDFFF) {
                continue;
            }
            String name = "a" + Character.toString(cp) + "b.csv";
            int type = Character.getType(cp);
            boolean folded = cp == '"' || cp == '\\' || type == Character.CONTROL
                    || (type == Character.FORMAT && cp != 0x200C && cp != 0x200D)
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
            assertThat(ContentDisposition.sanitizeFilename(name)).as("U+%04X", cp)
                    .isEqualTo(folded ? "a_b.csv" : name);
        }
        // The members a reader would look for by name, once each.
        for (int cp : new int[]{0x00, 0x09, 0x1B, 0x1F, 0x7F, 0x80, 0x85, 0x9F, 0x061C, 0x200B,
                0x200E, 0x200F, 0x202A, 0x202B, 0x202D, 0x202E, 0x2060, 0x2066, 0x2068, 0x2069,
                0xFEFF, 0x00AD, 0x2028, 0xE0041}) {
            assertThat(ContentDisposition.sanitizeFilename("a" + Character.toString(cp) + "b"))
                    .as("U+%04X", cp).isEqualTo("a_b");
        }
    }

    /** Both halves are built from the sanitized name: a folded character reaches neither. */
    @Test
    void aFoldedCharacterReachesNeitherHalfOfANonAsciiName() {
        assertThat(ContentDisposition.attachment("受注\".csv"))
                .isEqualTo(
                        "attachment; filename=\"___.csv\"; filename*=UTF-8''%E5%8F%97%E6%B3%A8_.csv");
        assertThat(ContentDisposition.attachment("受注\\.csv"))
                .isEqualTo(
                        "attachment; filename=\"___.csv\"; filename*=UTF-8''%E5%8F%97%E6%B3%A8_.csv");
        assertThat(ContentDisposition.attachment("受注\u202Egpj.exe")).isEqualTo(
                "attachment; filename=\"___gpj.exe\"; filename*=UTF-8''%E5%8F%97%E6%B3%A8_gpj.exe");
        assertThat(ContentDisposition.attachment("受\r\n注.csv")).isEqualTo(
                "attachment; filename=\"____.csv\"; filename*=UTF-8''%E5%8F%97__%E6%B3%A8.csv");
        assertThat(ContentDisposition.attachment("受注\u0000.csv"))
                .isEqualTo(
                        "attachment; filename=\"___.csv\"; filename*=UTF-8''%E5%8F%97%E6%B3%A8_.csv");
    }

    /** The fold precedes the gate: a name that is ASCII once folded stays single-form. */
    @Test
    void aControlIsFoldedBeforeTheGateSoAnAsciiNameStaysSingleForm() {
        for (String name : List.of("a\u0092b.csv", "a\tb.csv", "a\u0085b.csv", "a\u007Fb.csv",
                "a\u001Bb.csv")) {
            assertThat(ContentDisposition.attachment(name)).as(name)
                    .isEqualTo("attachment; filename=\"a_b.csv\"");
        }
        for (String name : List.of("inv\u202Egpj.exe", "inv\u061Cgpj.exe", "inv\u2060gpj.exe")) {
            assertThat(ContentDisposition.attachment(name)).as(name)
                    .isEqualTo("attachment; filename=\"inv_gpj.exe\"");
        }
    }

    /** The joiners are kept on both halves: an underscore inside a Persian word is a wrong name. */
    @Test
    void aJoinerIsPartOfTheNameOnTheEncodedHalf() {
        assertThat(ContentDisposition.attachment("می\u200Cخواهم.csv"))
                .isEqualTo("attachment; filename=\"________.csv\"; filename*=UTF-8''"
                        + "%D9%85%DB%8C%E2%80%8C%D8%AE%D9%88%D8%A7%D9%87%D9%85.csv");
        assertThat(ContentDisposition.attachment("👨\u200D👩\u200D👧.csv"))
                .isEqualTo("attachment; filename=\"_____.csv\"; filename*=UTF-8''"
                        + "%F0%9F%91%A8%E2%80%8D%F0%9F%91%A9%E2%80%8D%F0%9F%91%A7.csv");
    }

    /** {@code filename*}'s own delimiters ({@code ' * %}) and every non-attr-char are escaped. */
    @Test
    void theEncodedHalfEscapesEveryDelimiterOfItsOwnGrammar() {
        assertThat(ContentDisposition.attachment("受注 (1);x='%*.csv")).isEqualTo(
                "attachment; filename=\"__ (1);x='%*.csv\";"
                        + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%20%281%29%3Bx%3D%27%25%2A.csv");
        assertThat(ContentDisposition.attachment("受注;filename=evil.exe.csv")).isEqualTo(
                "attachment; filename=\"__;filename=evil.exe.csv\";"
                        + " filename*=UTF-8''%E5%8F%97%E6%B3%A8%3Bfilename%3Devil.exe.csv");
    }

    @Test
    void aNullNameIsAProgrammingError() {
        assertThat(ContentDisposition.sanitizeFilename(null)).isNull();
        assertThatNullPointerException().isThrownBy(() -> ContentDisposition.attachment(null))
                .withMessage("filename");
    }

    /** The fold's count is one per code point, an astral tag character included. */
    @Test
    void anAstralFormatCharacterFoldsToOneUnderscore() {
        assertThat(ContentDisposition.sanitizeFilename("tag\uDB40\uDC41\uDB40\uDC42.pdf"))
                .isEqualTo("tag__.pdf");
    }
}
