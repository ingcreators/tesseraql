package io.tesseraql.yaml.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the base-path rule is, expressed as the renders it produces (docs/base-path.md decision 2).
 *
 * <p>These are the properties the framework's templates rely on when they hand a model-supplied
 * URL to a link expression — {@code th:href="@{${v.action}}"} — rather than emitting it raw.
 */
class BasePathLinkBuilderTest {

    @Test
    void anUnsetBaseRendersTheUrlUnchanged(@TempDir Path root) throws IOException {
        assertThat(render(root, Map.of("url", "/items?page=2")))
                .contains("href=\"/assets/x.css\"")
                .contains("href=\"/items?page=2\"");
    }

    @Test
    void aBaseIsPrependedToLiteralAndModelUrlsAlike(@TempDir Path root) throws IOException {
        String html = render(root, Map.of("base", "/apps/shop-a", "url", "/items?page=2"));

        assertThat(html).contains("href=\"/apps/shop-a/assets/x.css\"");
        assertThat(html)
                .as("a query string survives the round trip through the link expression")
                .contains("href=\"/apps/shop-a/items?page=2\"");
    }

    @Test
    void anAbsoluteUrlIsLeftAlone(@TempDir Path root) throws IOException {
        assertThat(render(root, Map.of("base", "/apps/shop-a",
                "url", "https://example.test/docs")))
                .contains("href=\"https://example.test/docs\"");
    }

    /**
     * The empty model URL, which is how the view patterns say "this element has no live stream":
     * an empty value must stay empty, or {@code th:attr} would emit the bare prefix as an address
     * and htmx would connect to it.
     */
    @Test
    void anEmptyUrlStaysEmpty(@TempDir Path root) throws IOException {
        assertThat(render(root, Map.of("base", "/apps/shop-a", "url", "")))
                .contains("href=\"\"")
                .doesNotContain("href=\"/apps/shop-a\"");
    }

    private static String render(Path root, Map<String, Object> model) throws IOException {
        Files.writeString(root.resolve("page.html"),
                "<html><body>"
                        + "<a th:href=\"@{/assets/x.css}\">a</a>"
                        + "<a th:href=\"@{${url}}\">b</a>"
                        + "</body></html>");
        return Templates.render(root, "page.html", model);
    }
}
