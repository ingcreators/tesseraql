package io.tesseraql.cli.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.compiler.binding.RouteTelemetry;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * Trace-id correlation (roadmap Phase 45), asserted here because the CLI's structured log
 * provider supplies the real MDC adapter: the route span's ids land in the MDC when the
 * request starts processing, so every log line it produces carries them.
 */
class RouteTelemetryMdcTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void processPutsTheSpanIdsIntoTheMdc() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.getRegistry().bind(TesseraqlProperties.TRACER_BEAN,
                    new io.tesseraql.core.telemetry.RingTracer(10));
            Exchange exchange = new DefaultExchange(context);
            new RouteTelemetry("users.search", "GET", "/api/users", "app", false)
                    .process(exchange);

            assertThat(MDC.get("traceId")).isNotBlank();
            assertThat(MDC.get("spanId")).isNotBlank();
            assertThat(exchange.getProperty(TesseraqlProperties.TRACE_CONTEXT)).isNotNull();
        }
    }
}
