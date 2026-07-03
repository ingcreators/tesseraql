package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

/** The automatic pagination headers (roadmap Phase 41). */
class PageHeadersTest {

    private static Exchange exchange(Map<String, Object> page, String uri, String query) {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of("page", page));
        exchange.getMessage().setHeader(Exchange.HTTP_URI, uri);
        exchange.getMessage().setHeader(Exchange.HTTP_QUERY, query);
        return exchange;
    }

    @Test
    void emitsTotalCountAndNextPrevLinks() throws Exception {
        Exchange exchange = exchange(Map.of("number", 2L, "size", 20, "hasNext", true,
                "totalRows", 45L), "/items", "q=bolt&page=2");
        new PageHeaders().process(exchange);
        assertThat(exchange.getMessage().getHeader("X-Total-Count")).isEqualTo("45");
        assertThat(exchange.getMessage().getHeader("Link", String.class))
                .contains("</items?q=bolt&page=3>; rel=\"next\"")
                .contains("</items?q=bolt&page=1>; rel=\"prev\"");
    }

    @Test
    void keysetLinksCarryTheCursor() throws Exception {
        Exchange exchange = exchange(Map.of("number", 1L, "size", 2, "hasNext", true,
                "next", 42), "/items", null);
        new PageHeaders().process(exchange);
        assertThat(exchange.getMessage().getHeader("Link", String.class))
                .isEqualTo("</items?after=42>; rel=\"next\"");
    }

    @Test
    void theLastPageEmitsNoNextLink() throws Exception {
        Exchange exchange = exchange(Map.of("number", 1L, "size", 20, "hasNext", false),
                "/items", null);
        new PageHeaders().process(exchange);
        assertThat(exchange.getMessage().getHeader("Link")).isNull();
    }
}
