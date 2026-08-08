package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Heading anchors keep Unicode letters (docs/unicode-identifiers.md) — the ASCII-only class
 * slugged every Japanese heading to the empty string, colliding all anchors on the page.
 */
class ReferenceSlugTest {

    @Test
    void japaneseHeadingsKeepTheirAnchors() {
        assertThat(ReferenceGenerator.slug("受注 エラー一覧")).isEqualTo("受注-エラー一覧");
        assertThat(ReferenceGenerator.slug("Route Compiler")).isEqualTo("route-compiler");
        assertThat(ReferenceGenerator.slug("TQL-SQL (2xxx)")).isEqualTo("tql-sql-2xxx");
    }
}
