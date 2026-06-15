package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocMarkdownTest {

    @Test
    void rendersBasicMarkdownToHtml() {
        String html = DocMarkdown.toHtml("# Title\n\nSome **bold** text.\n");

        assertThat(html).contains("<h1>Title</h1>").contains("<strong>bold</strong>");
    }

    @Test
    void escapesRawHtmlSoInlineScriptCannotReachTheCspStrictPage() {
        String html = DocMarkdown.toHtml("Hello <script>alert('x')</script> world\n");

        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void sanitizesUnsafeLinkTargets() {
        String html = DocMarkdown.toHtml("[click](javascript:alert(1))\n");

        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void blankInputRendersBlank() {
        assertThat(DocMarkdown.toHtml(null)).isEmpty();
        assertThat(DocMarkdown.toHtml("   ")).isEmpty();
    }
}
