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
