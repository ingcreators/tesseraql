package io.tesseraql.cli.logging;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.camel.TesseraqlProperties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.mdc.MDCService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * The property that makes a trace id worth having: a step handed to another thread still logs
 * with the ids of the request that started it. Asserted here rather than in the runtime module
 * because the CLI's structured log provider supplies the real MDC adapter — without it every
 * {@code MDC.get} answers null and a test like this passes or fails for the wrong reason.
 *
 * <p>The mechanism is the one {@code TesseraqlRuntime} installs: ids travel as exchange
 * properties, and the MDC service copies them onto whichever thread is running the processor.
 */
class MdcAcrossThreadsTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    void theTraceIdsFollowTheExchangeAcrossAThreadHandoff() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            MDCService mdc = new MDCService();
            mdc.setCustomProperties(
                    TesseraqlProperties.TRACE_ID + "," + TesseraqlProperties.SPAN_ID);
            mdc.init(context);

            AtomicReference<String> traceOnFarSide = new AtomicReference<>();
            AtomicReference<String> spanOnFarSide = new AtomicReference<>();
            AtomicReference<String> farThread = new AtomicReference<>();
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start")
                            .process(exchange -> {
                                exchange.setProperty(TesseraqlProperties.TRACE_ID, "trace-1");
                                exchange.setProperty(TesseraqlProperties.SPAN_ID, "span-1");
                            })
                            // What an execution lane does to a step: hand it to another thread.
                            .threads(1, 1)
                            .process(exchange -> {
                                traceOnFarSide.set(MDC.get(TesseraqlProperties.TRACE_ID));
                                spanOnFarSide.set(MDC.get(TesseraqlProperties.SPAN_ID));
                                farThread.set(Thread.currentThread().getName());
                            });
                }
            });
            context.start();

            String callerThread = Thread.currentThread().getName();
            context.createProducerTemplate().sendBody("direct:start", "body");

            // The handoff has to be real, or the assertion below proves nothing.
            assertThat(farThread.get()).isNotEqualTo(callerThread);
            assertThat(traceOnFarSide.get()).isEqualTo("trace-1");
            assertThat(spanOnFarSide.get()).isEqualTo("span-1");
        }
    }

    @Test
    void camelsOwnIdentifiersRideAlongWithoutBeingThreadedThrough() throws Exception {
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            new MDCService().init(context);

            AtomicReference<String> routeId = new AtomicReference<>();
            AtomicReference<String> exchangeId = new AtomicReference<>();
            context.addRoutes(new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:start").routeId("the.route")
                            .process(exchange -> {
                                routeId.set(MDC.get("camel.routeId"));
                                exchangeId.set(MDC.get("camel.exchangeId"));
                            });
                }
            });
            context.start();
            context.createProducerTemplate().sendBody("direct:start", "body");

            assertThat(routeId.get()).isEqualTo("the.route");
            assertThat(exchangeId.get()).isNotBlank();
        }
    }
}
