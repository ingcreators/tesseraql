package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.RedirectResponse;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
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

        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(303);
        assertThat(exchange.getMessage().getHeader("Location")).isEqualTo("/items/42");
        assertThat(exchange.getMessage().getHeader("HX-Redirect")).isNull();
    }

    @Test
    void htmxCallerGets204AndHxRedirect() {
        Exchange exchange = exchange("true");

        renderer.process(exchange);

        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(204);
        assertThat(exchange.getMessage().getHeader("HX-Redirect")).isEqualTo("/items/42");
        // No Location header — htmx navigates via HX-Redirect, not a transparent 3xx follow.
        assertThat(exchange.getMessage().getHeader("Location")).isNull();
    }

    @Test
    void configuredStatusIsHonoredForNoJsCallers() {
        RedirectRenderer seeOther = new RedirectRenderer(new RedirectResponse(302, "/items"));
        Exchange exchange = exchange(null);

        seeOther.process(exchange);

        assertThat(exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE)).isEqualTo(302);
        assertThat(exchange.getMessage().getHeader("Location")).isEqualTo("/items");
    }

    private static Exchange exchange(String hxRequest) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of("params", Map.of("id", 42)));
        if (hxRequest != null) {
            exchange.getMessage().setHeader("HX-Request", hxRequest);
        }
        return exchange;
    }
}
