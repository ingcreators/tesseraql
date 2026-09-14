package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The cookie path is published as wire text (docs/router-unicode-names.md R1): a browser
 * compares {@code Path=} against the percent-encoded request path it sent, so a path named in
 * Japanese has to be published encoded — raw, the transport folded it and no request ever
 * matched the cookie.
 */
class CookiePathTest {

    @Test
    void aNonAsciiCookiePathIsPublishedAsWireText() {
        RuntimeContext context = new RuntimeContext();
        CookiePath.bind(context, "/受注");
        assertThat(CookiePath.of(context.beans())).isEqualTo("/%E5%8F%97%E6%B3%A8");
    }

    @Test
    void anAsciiCookiePathAndTheDefaultAreUnchanged() {
        RuntimeContext context = new RuntimeContext();
        CookiePath.bind(context, "/shop-a");
        assertThat(CookiePath.of(context.beans())).isEqualTo("/shop-a");
        RuntimeContext bare = new RuntimeContext();
        CookiePath.bind(bare, null);
        assertThat(CookiePath.of(bare.beans())).isEqualTo("/");
    }
}
