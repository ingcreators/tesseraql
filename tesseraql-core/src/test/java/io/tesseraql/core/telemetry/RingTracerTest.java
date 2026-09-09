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

    /**
     * {@code error=true} says a route failed and nothing else. The ring is what the operations
     * console's trace page reads, and it was the last place a failure's identity could have
     * survived: the error envelope carries the status phrase rather than the cause, and until
     * this was written nothing logged a route failure at all.
     */
    @Test
    void aRecordedErrorKeepsWhatFailed() {
        RingTracer tracer = new RingTracer(4);
        Span failing = tracer.start("route");
        failing.recordError(new IllegalStateException("relation \"orders\" does not exist"));
        failing.end();

        SpanSample sample = tracer.recentSpans().get(0);
        assertThat(sample.error()).isTrue();
        assertThat(sample.attributes())
                .containsEntry("error.type", "java.lang.IllegalStateException")
                .containsEntry("error.message", "relation \"orders\" does not exist");
    }

    /** A throwable with no message must not put a null in the ring. */
    @Test
    void aRecordedErrorWithoutAMessageKeepsItsType() {
        RingTracer tracer = new RingTracer(4);
        Span failing = tracer.start("route");
        failing.recordError(new IllegalStateException());
        failing.end();

        SpanSample sample = tracer.recentSpans().get(0);
        assertThat(sample.attributes())
                .containsEntry("error.type", "java.lang.IllegalStateException")
                .doesNotContainKey("error.message");
    }
}
