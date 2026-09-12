package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The seam: a framework-built URL acquires its prefix and its percent-encoding here, once, on
 * the joined result — every {@code BasePath.url} caller rides on this, not only the redirects.
 */
class BasePathTest {

    @Test
    void urlEncodesTheJoinedResultOnce() {
        RuntimeContext context = new RuntimeContext();
        BasePath.bind(context, "/受注");

        assertThat(BasePath.url(new Exchange(context.beans()), "/a b/é"))
                .isEqualTo("/%E5%8F%97%E6%B3%A8/a%20b/%C3%A9");
    }

    @Test
    void urlEncodesTheAssetBranchToo() {
        RuntimeContext context = new RuntimeContext();
        BasePath.bind(context, "/受注");

        assertThat(BasePath.url(new Exchange(context.beans()), "/assets/app.css"))
                .isEqualTo("/%E5%8F%97%E6%B3%A8/assets/app.css");
    }

    @Test
    void anAsciiUrlIsTheSameStringItWasHandedAfterTheJoin() {
        RuntimeContext context = new RuntimeContext();
        BasePath.bind(context, "/apps/shop-a");

        assertThat(BasePath.url(new Exchange(context.beans()), "/items?page=2#row-1"))
                .isEqualTo("/apps/shop-a/items?page=2#row-1");
        assertThat(BasePath.url(null, null)).isNull();
    }
}
