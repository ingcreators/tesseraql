package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The identifier contract (docs/unicode-identifiers.md): Unicode letters are names, SQL
 * metacharacters are not — the class is the injection defense for identifiers that land
 * verbatim in SQL text, so the rejections matter as much as the acceptances.
 */
class SqlIdentifiersTest {

    @Test
    void unicodeLettersAreIdentifiers() {
        assertThat(SqlIdentifiers.isIdentifier("order_lines")).isTrue();
        assertThat(SqlIdentifiers.isIdentifier("顧客")).isTrue();
        assertThat(SqlIdentifiers.isIdentifier("顧客_名前")).isTrue();
        assertThat(SqlIdentifiers.isIdentifier("受注明細2")).isTrue();
        assertThat(SqlIdentifiers.isIdentifier("_interne")).isTrue();
        assertThat(SqlIdentifiers.isIdentifier("Bestellungen")).isTrue();
    }

    @Test
    void combiningMarksAreIdentifiers() {
        // Every abugida requires a mark, and decomposed text produces one for scripts that have a
        // composed form — which macOS emits (docs/two-way-sql-parser.md decision 11).
        assertThat(SqlIdentifiers.isIdentifier("ग्राहक")).isTrue(); // Devanagari: virama + matra
        assertThat(SqlIdentifiers.isIdentifier("مُحَمَّد")).isTrue(); // Arabic harakat
        assertThat(SqlIdentifiers.isIdentifier("ยิ้ม")).isTrue(); // Thai tone mark
        assertThat(SqlIdentifiers.isIdentifier(nfd("Việt"))).isTrue();
        assertThat(SqlIdentifiers.isIdentifier(nfd("が"))).isTrue();
    }

    @Test
    void aMarkMayNotStartAName() {
        // A leading combining mark is a rendering trick, never a real name.
        assertThat(SqlIdentifiers.isIdentifier("́abc")).isFalse();
    }

    @Test
    void displayOnlyAndInvisibleMarksAreNot() {
        assertThat(SqlIdentifiers.isIdentifier("a⃝")).isFalse(); // enclosing mark: display only
        assertThat(SqlIdentifiers.isIdentifier("a‌b")).isFalse(); // ZWNJ: invisible, spoofable
        assertThat(SqlIdentifiers.isIdentifier("a‍b")).isFalse(); // ZWJ
    }

    @Test
    void theContractAgreesWithTheBindLexerOnEveryNameItAdmits() {
        // The 2-way bind lexer accepted all of these all along; the contract refused them, so the
        // two halves of the framework disagreed about what a name is.
        for (String name : java.util.List.of("ग्राहक", "مُحَمَّد", "ยิ้ม", nfd("Việt"), nfd("が"),
                "顧客")) {
            assertThat(SqlIdentifiers.isIdentifier(name)).as(name).isTrue();
            for (int i = 0; i < name.length(); i++) {
                assertThat(Character.isJavaIdentifierPart(name.charAt(i)))
                        .as(name + " at " + i).isTrue();
            }
        }
    }

    @Test
    void aMarkCanNeverBecomeSqlSyntax() {
        // The class is the injection defense, and the widening has to preserve that: no combining
        // mark is ASCII, normalizes to an ASCII non-alphanumeric, or case-maps to one.
        for (int c = 0; c <= Character.MAX_CODE_POINT; c++) {
            int type = Character.getType(c);
            if (type != Character.NON_SPACING_MARK && type != Character.COMBINING_SPACING_MARK) {
                continue;
            }
            assertThat(c).as("a mark must not be ASCII").isGreaterThan(127);
            String mark = new String(Character.toChars(c));
            for (java.text.Normalizer.Form form : java.text.Normalizer.Form.values()) {
                assertThat(java.text.Normalizer.normalize(mark, form).chars())
                        .as("normalized " + Integer.toHexString(c))
                        .noneMatch(ch -> ch < 128 && !Character.isLetterOrDigit(ch) && ch != '_');
            }
        }
    }

    private static String nfd(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
    }

    @Test
    void sqlMetacharactersAreNot() {
        assertThat(SqlIdentifiers.isIdentifier(null)).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("2fast")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a-b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a;b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a'b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a\"b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a--b")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("顧客; drop table x")).isFalse();
        assertThat(SqlIdentifiers.isIdentifier("a.b")).isFalse();
    }

    @Test
    void dottedAllowsOneQualifier() {
        assertThat(SqlIdentifiers.isDotted("顧客")).isTrue();
        assertThat(SqlIdentifiers.isDotted("販売.顧客")).isTrue();
        assertThat(SqlIdentifiers.isDotted("public.orders")).isTrue();
        assertThat(SqlIdentifiers.isDotted("a.b.c")).isFalse();
        assertThat(SqlIdentifiers.isDotted(".a")).isFalse();
        assertThat(SqlIdentifiers.isDotted("a.")).isFalse();
        assertThat(SqlIdentifiers.isDotted(null)).isFalse();
    }
}
