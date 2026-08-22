package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.binding.RouteTelemetry;
import io.tesseraql.compiler.pipeline.Pipelines;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.telemetry.Span;
import io.tesseraql.core.telemetry.Tracer;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A failing route's span carries its exception (docs/vertx-native.md decision 5, slice 1).
 *
 * <p>This asserts the property the two-method completion silently did not have: the error branch
 * read {@code getException()}, the pipeline moves the exception to the
 * {@code EXCEPTION_CAUGHT} property before draining, and so no span this framework emitted ever
 * recorded its error. The completion is one method now and reads the property — and this test is
 * what notices if that regresses, because a passing suite is exactly what the defect looked like.
 */
class RouteFailureSpanTest {

    private static final TqlErrorCode BOOM = new TqlErrorCode(TqlDomain.ROUTE, 9999);

    /** Records what a real tracer would export, so the assertion reads the artifact. */
    private static final class RecordingSpan implements Span {
        private Throwable error;
        private boolean ended;
        private final java.util.Map<String, Object> attributes = new java.util.LinkedHashMap<>();

        @Override
        public Span attribute(String key, Object value) {
            attributes.put(key, value);
            return this;
        }

        @Override
        public void recordError(Throwable failure) {
            error = failure;
        }

        @Override
        public void end() {
            ended = true;
        }
    }

    @Test
    void aHandledFailureIsRecordedOnTheSpan() {
        try (RuntimeContext context = new RuntimeContext()) {
            RecordingSpan span = new RecordingSpan();
            context.bind(TesseraqlProperties.TRACER_BEAN, (Tracer) name -> span);

            TqlException failure = new TqlException(BOOM, "the step refused");
            Pipelines.of(context).compiling(List.of()).pipeline("t.fails")
                    .process(new RouteTelemetry("t.fails", "GET", "/t", null))
                    .onException(List.of(TqlException.class.getName()), rendered -> rendered
                            .getMessage().setHeader(Headers.HTTP_RESPONSE_CODE, 500))
                    .process(exchange -> {
                        throw failure;
                    });

            Exchange answered = RoutePipelines.of(context)
                    .run("t.fails", exchange -> exchange.setFromRouteId("t.fails"))
                    .orElseThrow();

            assertThat(answered.getMessage().getHeader(Headers.HTTP_RESPONSE_CODE))
                    .as("the clause answered")
                    .isEqualTo(500);
            assertThat(span.ended).isTrue();
            assertThat(span.error)
                    .as("the failure the clause answered for reaches the span")
                    .isSameAs(failure);
            assertThat(span.attributes).containsEntry("status", 500);
        }
    }

    @Test
    void aSuccessfulRouteRecordsNoError() {
        try (RuntimeContext context = new RuntimeContext()) {
            RecordingSpan span = new RecordingSpan();
            context.bind(TesseraqlProperties.TRACER_BEAN, (Tracer) name -> span);

            Pipelines.of(context).compiling(List.of()).pipeline("t.ok")
                    .process(new RouteTelemetry("t.ok", "GET", "/t", null))
                    .process(exchange -> exchange.getMessage()
                            .setHeader(Headers.HTTP_RESPONSE_CODE, 200));

            RoutePipelines.of(context).run("t.ok", exchange -> exchange.setFromRouteId("t.ok"))
                    .orElseThrow();

            assertThat(span.ended).isTrue();
            assertThat(span.error).isNull();
        }
    }
}
