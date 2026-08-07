package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.PageBuilder.Parts;
import org.junit.jupiter.api.Test;

/**
 * The builder's eligibility rule and byte-safe split (docs/page-builder.md D1): the two
 * accepted shapes reassemble verbatim from prefix + region + suffix, and everything else
 * — mail templates included — stays with the source editor.
 */
class PageBuilderTest {

    /** The shape ViewEjector emits and studio-authored pages share. */
    private static final String EJECTED_PAGE = """
            <!DOCTYPE html>
            <!-- Ejected from users.view.yml (tesseraql scaffold eject-view): the tql/view/list
                 pattern pinned to this page. -->
            <html xmlns:th="http://www.thymeleaf.org"
                  th:replace="~{tql/shell :: shell('Users', ~{templates/nav.html :: app-nav}, ~{}, ~{:: #page-content})}">
            <!-- A comment between the wrapper and the page div survives verbatim. -->
            <div id="page-content" class="hc-stack">
            <section class="hc-card">
              <div class="hc-card__header">Users</div>
              <div class="hc-card__body hc-stack">
                <p th:text="${greeting}">Hello</p>
                <div th:if="${n > 0}" class="hc-cluster"><span class="hc-badge">n</span></div>
              </div>
            </section>
            </div>
            </html>
            """;

    @Test
    void splitsAShellWrappedPageByteSafely() {
        Parts parts = PageBuilder.parse(EJECTED_PAGE).orElseThrow();

        assertThat(parts.shellWrapped()).isTrue();
        assertThat(parts.prefix() + parts.region() + parts.suffix()).isEqualTo(EJECTED_PAGE);
        // The checksum-style header and the wrapper live in the verbatim prefix.
        assertThat(parts.prefix()).contains("Ejected from").contains("tql/shell :: shell")
                .contains("A comment between").endsWith("class=\"hc-stack\">");
        assertThat(parts.regionClass()).isEqualTo("hc-stack");
        // Nested divs (including one whose attr value contains '>') stay in the region.
        assertThat(parts.region()).contains("hc-card__body").contains("${n > 0}");
        assertThat(parts.suffix()).isEqualTo("</div>\n</html>\n");
    }

    @Test
    void aBareFragmentFileIsTheWholeRegion() {
        String fragment = """
                <th:block xmlns:th="http://www.thymeleaf.org" th:fragment="app-nav">
                  <a class="hc-item" href="/users">Users</a>
                </th:block>
                """;

        Parts parts = PageBuilder.parse(fragment).orElseThrow();

        assertThat(parts.shellWrapped()).isFalse();
        assertThat(parts.prefix()).isEmpty();
        assertThat(parts.suffix()).isEmpty();
        assertThat(parts.region()).isEqualTo(fragment);
        assertThat(parts.regionClass()).isEmpty();
    }

    @Test
    void rejectsEverythingElse() {
        // Null/blank, a full document with a hand-written head, a non-shell wrapper,
        // markup between the wrapper and the page div, and a page div that never closes.
        assertThat(PageBuilder.parse(null)).isEmpty();
        assertThat(PageBuilder.parse("   ")).isEmpty();
        assertThat(PageBuilder.parse(
                "<html><head><title>x</title></head><body>custom</body></html>")).isEmpty();
        assertThat(PageBuilder.parse(EJECTED_PAGE.replace("tql/shell", "my/shell"))).isEmpty();
        assertThat(PageBuilder.parse(EJECTED_PAGE.replace(
                "<div id=\"page-content\" class=\"hc-stack\">",
                "<p>stray</p><div id=\"page-content\" class=\"hc-stack\">"))).isEmpty();
        assertThat(PageBuilder.parse(EJECTED_PAGE.replace("</div>\n</html>", "</html>")))
                .isEmpty();
        // The canvas seeds from an inert <template> element — a region carrying its own
        // </template> would break out of it, so the file stays with the source editor.
        assertThat(PageBuilder.parse(EJECTED_PAGE.replace("<p th:text=\"${greeting}\">Hello</p>",
                "<template><p>x</p></template>"))).isEmpty();
    }

    @Test
    void mailTemplatesParseAsFragmentsButTheComposerOwnsThem() {
        // A composable mail template has no <html> root, so PageBuilder itself accepts it
        // as a fragment — the entry point routes it to the mail composer instead (the
        // builder button requires PageBuilder-eligible AND not MailComposer-composable).
        String mail = """
                <div th:replace="~{tql/email/hc-email-layout :: hcLayout('T', 'P', ~{:: content})}">
                  <div th:fragment="content"></div>
                </div>
                """;
        assertThat(PageBuilder.parse(mail)).isNotEmpty();
        assertThat(MailComposer.parse(mail)).isNotEmpty();
    }
}
