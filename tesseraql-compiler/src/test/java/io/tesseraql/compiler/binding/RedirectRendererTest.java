package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.RedirectResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The redirect renderer's post/redirect/get branch (Hypermedia Components mutating-form recipe):
 * htmx callers get {@code 204} + {@code HX-Redirect}, no-JS callers get the plain {@code Location}.
 */
class RedirectRendererTest {

    private final RedirectRenderer renderer = new RedirectRenderer(
            new RedirectResponse(null, "/items/{params.id}"));

    @Test
    void noJsFormPostGetsAPlainLocationRedirect() {
        Exchange exchange = exchange(null);

        renderer.process(exchange);

        assertThat(exchange.response().status()).isEqualTo(303);
        assertThat(exchange.response().header("Location")).isEqualTo("/items/42");
        assertThat(exchange.response().header("HX-Redirect")).isNull();
    }

    @Test
    void htmxCallerGets204AndHxRedirect() {
        Exchange exchange = exchange("true");

        renderer.process(exchange);

        assertThat(exchange.response().status()).isEqualTo(204);
        assertThat(exchange.response().header("HX-Redirect")).isEqualTo("/items/42");
        // No Location header — htmx navigates via HX-Redirect, not a transparent 3xx follow.
        assertThat(exchange.response().header("Location")).isNull();
    }

    @Test
    void configuredStatusIsHonoredForNoJsCallers() {
        RedirectRenderer seeOther = new RedirectRenderer(new RedirectResponse(302, "/items"));
        Exchange exchange = exchange(null);

        seeOther.process(exchange);

        assertThat(exchange.response().status()).isEqualTo(302);
        assertThat(exchange.response().header("Location")).isEqualTo("/items");
    }

    /**
     * A redirect names an address the browser will ask for, so under a base path it must name one
     * this runtime serves (docs/base-path.md slice 2). Both branches carry it, because htmx
     * navigates on {@code HX-Redirect} exactly as the browser navigates on {@code Location}.
     */
    @Test
    void aRedirectCarriesTheApplicationsBasePath() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.pipeline.BasePath.bind(context, "/apps/shop-a");

        Exchange plain = new Exchange(context.beans());
        plain.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        renderer.process(plain);
        assertThat(plain.response().header("Location")).isEqualTo("/apps/shop-a/items/42");

        Exchange htmx = new Exchange(context.beans());
        htmx.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        htmx.request().header("HX-Request", "true");
        renderer.process(htmx);
        assertThat(htmx.response().header("HX-Redirect")).isEqualTo("/apps/shop-a/items/42");
    }

    /** An off-site redirect is not this application's to prefix. */
    @Test
    void anAbsoluteRedirectIsLeftAlone() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.pipeline.BasePath.bind(context, "/apps/shop-a");
        Exchange exchange = new Exchange(context.beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of());

        new RedirectRenderer(new RedirectResponse(303, "https://example.test/pay"))
                .process(exchange);

        assertThat(exchange.response().header("Location"))
                .isEqualTo("https://example.test/pay");
    }

    // location: back (docs/list-surface.md decision 11): the target is the request's _return
    // field, validated app-local, never interpolated; anything else falls back to the root.

    @Test
    void backFollowsAValidatedReturnField() {
        RedirectRenderer back = new RedirectRenderer(new RedirectResponse(null, "back"));
        Exchange exchange = exchange(null);
        exchange.request().formFields().put("_return",
                java.util.List.of("/things?page=2#row-Nw"));

        back.process(exchange);

        assertThat(exchange.response().status()).isEqualTo(303);
        assertThat(exchange.response().header("Location"))
                .isEqualTo("/things?page=2#row-Nw");
    }

    @Test
    void backCarriesTheApplicationsBasePath() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.pipeline.BasePath.bind(context, "/apps/shop-a");
        Exchange exchange = new Exchange(context.beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of());
        exchange.request().formFields().put("_return", java.util.List.of("/things"));

        new RedirectRenderer(new RedirectResponse(null, "back")).process(exchange);

        assertThat(exchange.response().header("Location")).isEqualTo("/apps/shop-a/things");
    }

    @Test
    void backWithoutAReturnFieldFallsBackToTheRoot() {
        RedirectRenderer back = new RedirectRenderer(new RedirectResponse(null, "back"));
        Exchange exchange = exchange(null);

        back.process(exchange);

        assertThat(exchange.response().header("Location")).isEqualTo("/");
    }

    @Test
    void backRefusesAnOffSiteReturnField() {
        RedirectRenderer back = new RedirectRenderer(new RedirectResponse(null, "back"));
        for (String hostile : java.util.List.of("https://evil.example/x", "//evil.example/x",
                "/\\evil.example", "/x\r\nSet-Cookie: a=b", "relative/path",
                "/\t/evil.example", "/\t\\evil.example")) {
            Exchange exchange = exchange(null);
            exchange.request().formFields().put("_return", java.util.List.of(hostile));

            back.process(exchange);

            assertThat(exchange.response().header("Location")).as(hostile).isEqualTo("/");
        }
    }

    @Test
    void backAnswersHtmxWithHxRedirect() {
        RedirectRenderer back = new RedirectRenderer(new RedirectResponse(null, "back"));
        Exchange exchange = exchange("true");
        exchange.request().formFields().put("_return", java.util.List.of("/things#row-Nw"));

        back.process(exchange);

        assertThat(exchange.response().status()).isEqualTo(204);
        assertThat(exchange.response().header("HX-Redirect")).isEqualTo("/things#row-Nw");
    }

    // The Location half of F125: a non-ASCII target reaches the header percent-encoded,
    // once, at BasePath.url; a placeholder value is a path segment; a control in _return is
    // refused. (Only the literal half needed a new encoder — the value half was encoded.)

    @Test
    void aLiteralJapaneseLocationIsPercentEncoded() {
        assertThat(render("/受注一覧", null)).isEqualTo("/%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7");
    }

    @Test
    void aLatin1LocationIsPercentEncodedAsUtf8() {
        assertThat(render("/café", null)).isEqualTo("/caf%C3%A9");
    }

    @Test
    void aPreEncodedLocationIsNotEncodedTwice() {
        assertThat(render("/caf%C3%A9", null)).isEqualTo("/caf%C3%A9");
    }

    @Test
    void aPreEncodedTripletBesideAJapaneseSegmentIsNotEncodedTwice() {
        assertThat(render("/受注/caf%C3%A9", null)).isEqualTo("/%E5%8F%97%E6%B3%A8/caf%C3%A9");
    }

    @Test
    void theQueryAndFragmentOfALiteralLocationAreLeftAlone() {
        assertThat(render("/受注一覧?x=1&y=%E3%81%82#top", null))
                .isEqualTo("/%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7?x=1&y=%E3%81%82#top");
    }

    @Test
    void aFormEncodedSpaceInAQueryKeepsItsPlus() {
        assertThat(render("/受注一覧?q=a+b", null))
                .isEqualTo("/%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7?q=a+b");
    }

    @Test
    void anAstralLiteralIsEncodedAsFourBytes() {
        assertThat(render("/a😀b", null)).isEqualTo("/a%F0%9F%98%80b");
    }

    @Test
    void anAbsoluteRedirectWithANonAsciiPathIsEncodedToo() {
        assertThat(render("https://example.test/受注", null))
                .isEqualTo("https://example.test/%E5%8F%97%E6%B3%A8");
    }

    @Test
    void anExpressionValueWithASpaceIsPercent20() {
        assertThat(renderWith("/items/{params.name}", Map.of("params", Map.of("name", "a b"))))
                .isEqualTo("/items/a%20b");
    }

    @Test
    void anExpressionValueInJapaneseIsEncodedOnce() {
        assertThat(renderWith("/items/{params.jp}", Map.of("params", Map.of("jp", "受注"))))
                .isEqualTo("/items/%E5%8F%97%E6%B3%A8");
    }

    @Test
    void anExpressionValueWithASlashStaysASegment() {
        assertThat(renderWith("/items/{params.name}", Map.of("params", Map.of("name", "a/b"))))
                .isEqualTo("/items/a%2Fb");
    }

    @Test
    void anExpressionValueCannotBecomeAnOffSiteTarget() {
        assertThat(renderWith("/{params.next}", Map.of("params", Map.of("next", "//evil.test"))))
                .isEqualTo("/%2F%2Fevil.test");
        assertThat(renderWith("/{params.next}", Map.of("params", Map.of("next", "x?next=y"))))
                .isEqualTo("/x%3Fnext%3Dy");
    }

    @Test
    void backWithAJapaneseReturnFieldIsPercentEncoded() {
        assertThat(back("/受注一覧?page=2#row-Nw"))
                .isEqualTo("/%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7?page=2#row-Nw");
    }

    @Test
    void backWithAnEncodedReturnFieldIsNotEncodedTwice() {
        assertThat(back("/caf%C3%A9")).isEqualTo("/caf%C3%A9");
    }

    @Test
    void backWithAMixedReturnFieldIsEncodedOnce() {
        assertThat(back("/受注/caf%C3%A9")).isEqualTo("/%E5%8F%97%E6%B3%A8/caf%C3%A9");
    }

    @Test
    void aJapaneseBasePathIsPercentEncoded() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.pipeline.BasePath.bind(context, "/受注");
        Exchange exchange = new Exchange(context.beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));

        renderer.process(exchange);

        assertThat(exchange.response().header("Location"))
                .isEqualTo("/%E5%8F%97%E6%B3%A8/items/42");
    }

    @Test
    void htmxCallerGetsTheEncodedTarget() {
        assertThat(render("/受注一覧", "true")).isEqualTo("/%E5%8F%97%E6%B3%A8%E4%B8%80%E8%A6%A7");
    }

    private static String render(String location, String hxRequest) {
        return render(location, hxRequest, Map.of("params", Map.of("id", 42)));
    }

    private static String renderWith(String location, Map<String, Object> context) {
        return render(location, null, context);
    }

    private static String render(String location, String hxRequest, Map<String, Object> context) {
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        if (hxRequest != null) {
            exchange.request().header("HX-Request", hxRequest);
        }
        new RedirectRenderer(new RedirectResponse(null, location)).process(exchange);
        return hxRequest == null
                ? exchange.response().header("Location")
                : exchange.response().header("HX-Redirect");
    }

    private static String back(String returnField) {
        Exchange exchange = exchange(null);
        exchange.request().formFields().put("_return", java.util.List.of(returnField));
        new RedirectRenderer(new RedirectResponse(null, "back")).process(exchange);
        return exchange.response().header("Location");
    }

    private static Exchange exchange(String hxRequest) {
        Exchange exchange = new Exchange(
                Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        if (hxRequest != null) {
            exchange.request().header("HX-Request", hxRequest);
        }
        return exchange;
    }
}
