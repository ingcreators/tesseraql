package io.tesseraql.core.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CompositeTracerTest {

    @Test
    void fansSpansOutToAllDelegates() {
        RingTracer ring = new RingTracer(4);
        RecordingTracer recording = new RecordingTracer();
        CompositeTracer composite = new CompositeTracer(ring, recording);

        Span span = composite.start("tesseraql.route").attribute("routeId", "users.search");
        // context() comes from the first delegate (the in-process ring).
        assertThat(span.context()).isNotNull();
        span.end();

        assertThat(ring.recentSpans()).extracting(SpanSample::name).contains("tesseraql.route");
        assertThat(recording.spans()).extracting(RecordingTracer.RecordedSpan::name)
                .contains("tesseraql.route");
        assertThat(recording.spans()).anySatisfy(s ->
                assertThat(s.attributes()).containsEntry("routeId", "users.search"));
    }
}
