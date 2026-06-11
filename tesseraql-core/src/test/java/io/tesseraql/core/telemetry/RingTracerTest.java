package io.tesseraql.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RingTracerTest {

    @Test
    void recordsFinishedSpansMostRecentFirst() {
        RingTracer tracer = new RingTracer(10);
        tracer.start("tesseraql.route").attribute("routeId", "users.search").end();
        Span sql = tracer.start("tesseraql.sql.execute").attribute("rowCount", 3);
        sql.end();

        assertThat(tracer.recentSpans()).extracting(SpanSample::name)
                .containsExactly("tesseraql.sql.execute", "tesseraql.route");
        assertThat(tracer.recentSpans().get(0).attributes()).containsEntry("rowCount", 3);
        assertThat(tracer.recentSpans().get(0).durationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void capturesErrorsAndBoundsTheRing() {
        RingTracer tracer = new RingTracer(2);
        Span failing = tracer.start("a");
        failing.recordError(new RuntimeException("boom"));
        failing.end();
        tracer.start("b").end();
        tracer.start("c").end();

        assertThat(tracer.recentSpans()).extracting(SpanSample::name).containsExactly("c", "b");
    }
}
