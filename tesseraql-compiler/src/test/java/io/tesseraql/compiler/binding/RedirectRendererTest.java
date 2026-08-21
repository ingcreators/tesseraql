package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.RuntimeContext;
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

        assertThat(exchange.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE)).isEqualTo(303);
        assertThat(exchange.getMessage().getHeader("Location")).isEqualTo("/items/42");
        assertThat(exchange.getMessage().getHeader("HX-Redirect")).isNull();
    }

    @Test
    void htmxCallerGets204AndHxRedirect() {
        Exchange exchange = exchange("true");

        renderer.process(exchange);

        assertThat(exchange.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE)).isEqualTo(204);
        assertThat(exchange.getMessage().getHeader("HX-Redirect")).isEqualTo("/items/42");
        // No Location header — htmx navigates via HX-Redirect, not a transparent 3xx follow.
        assertThat(exchange.getMessage().getHeader("Location")).isNull();
    }

    @Test
    void configuredStatusIsHonoredForNoJsCallers() {
        RedirectRenderer seeOther = new RedirectRenderer(new RedirectResponse(302, "/items"));
        Exchange exchange = exchange(null);

        seeOther.process(exchange);

        assertThat(exchange.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE)).isEqualTo(302);
        assertThat(exchange.getMessage().getHeader("Location")).isEqualTo("/items");
    }

    /**
     * A redirect names an address the browser will ask for, so under a base path it must name one
     * this runtime serves (docs/base-path.md slice 2). Both branches carry it, because htmx
     * navigates on {@code HX-Redirect} exactly as the browser navigates on {@code Location}.
     */
    @Test
    void aRedirectCarriesTheApplicationsBasePath() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.camel.BasePath.bind(context, "/apps/shop-a");

        Exchange plain = new Exchange(context.beans());
        plain.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        renderer.process(plain);
        assertThat(plain.getMessage().getHeader("Location")).isEqualTo("/apps/shop-a/items/42");

        Exchange htmx = new Exchange(context.beans());
        htmx.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        htmx.getMessage().setHeader("HX-Request", "true");
        renderer.process(htmx);
        assertThat(htmx.getMessage().getHeader("HX-Redirect")).isEqualTo("/apps/shop-a/items/42");
    }

    /** An off-site redirect is not this application's to prefix. */
    @Test
    void anAbsoluteRedirectIsLeftAlone() {
        RuntimeContext context = new RuntimeContext();
        io.tesseraql.camel.BasePath.bind(context, "/apps/shop-a");
        Exchange exchange = new Exchange(context.beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of());

        new RedirectRenderer(new RedirectResponse(303, "https://example.test/pay"))
                .process(exchange);

        assertThat(exchange.getMessage().getHeader("Location"))
                .isEqualTo("https://example.test/pay");
    }

    private static Exchange exchange(String hxRequest) {
        Exchange exchange = new Exchange(
                new RuntimeContext().beans());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        if (hxRequest != null) {
            exchange.getMessage().setHeader("HX-Request", hxRequest);
        }
        return exchange;
    }
}
