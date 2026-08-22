package io.tesseraql.compiler.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.pipeline.RuntimeContext;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The registry's built cache survives the compiler's register-then-fill order
 * (docs/vertx-native.md decision 4).
 *
 * <p>{@code Compilation.pipeline(id)} registers a builder before a single step is appended — so
 * "registered" and "finished" are different moments, and a lookup can land between them. A cache
 * keyed on registration alone would freeze whatever half-built chain that lookup saw; this pins
 * the version check that makes the race self-healing instead.
 */
class PipelinesCacheTest {

    @Test
    void aLookupDuringCompilationDoesNotFreezeTheHalfBuiltChain() {
        try (RuntimeContext context = new RuntimeContext()) {
            Pipelines pipelines = Pipelines.of(context);
            PipelineBuilder builder = pipelines.compiling(List.of()).pipeline("t.growing")
                    .process(exchange -> {
                    });

            assertThat(pipelines.find("t.growing").orElseThrow().steps())
                    .as("a racing lookup sees the chain as it stands")
                    .hasSize(1);

            builder.process(exchange -> {
            });

            assertThat(pipelines.find("t.growing").orElseThrow().steps())
                    .as("the finished chain is what later lookups get")
                    .hasSize(2);
        }
    }

    @Test
    void anUnchangedPipelineIsTheSameRecordOnEveryLookup() {
        try (RuntimeContext context = new RuntimeContext()) {
            Pipelines pipelines = Pipelines.of(context);
            pipelines.compiling(List.of()).pipeline("t.stable").process(exchange -> {
            });

            assertThat(pipelines.find("t.stable").orElseThrow())
                    .isSameAs(pipelines.find("t.stable").orElseThrow());
        }
    }

    /**
     * A lookup racing the compiler thread never tears. The builder's step list is filled on the
     * reloader's thread while {@code find} copies it on request threads; unsynchronized, the
     * copy threw {@code ConcurrentModificationException} on the request thread — a 500 for the
     * caller whose save it wasn't. The chain a racing lookup sees may still be the one mid-fill
     * (the test above pins that), but it is always a consistent snapshot.
     */
    @Test
    void aLookupRacingTheCompilerNeverTears() throws Exception {
        try (RuntimeContext context = new RuntimeContext()) {
            Pipelines pipelines = Pipelines.of(context);
            PipelineBuilder builder = pipelines.compiling(List.of()).pipeline("t.raced");
            java.util.concurrent.atomic.AtomicReference<Throwable> torn = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reader = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        pipelines.find("t.raced").ifPresent(pipeline -> pipeline.steps().size());
                    }
                } catch (Throwable failure) {
                    torn.set(failure);
                }
            });
            reader.start();
            for (int at = 0; at < 5_000; at++) {
                builder.process(exchange -> {
                });
            }
            reader.interrupt();
            reader.join(5_000);
            assertThat(torn.get()).as("a racing lookup must never observe a torn chain")
                    .isNull();
        }
    }

    @Test
    void aRecompileReplacesWhatEveryLookupGets() {
        try (RuntimeContext context = new RuntimeContext()) {
            Pipelines pipelines = Pipelines.of(context);
            pipelines.compiling(List.of()).pipeline("t.replaced").process(exchange -> {
            });
            Pipeline first = pipelines.find("t.replaced").orElseThrow();

            pipelines.compiling(List.of()).pipeline("t.replaced")
                    .process(exchange -> {
                    })
                    .process(exchange -> {
                    });

            Pipeline second = pipelines.find("t.replaced").orElseThrow();
            assertThat(second).isNotSameAs(first);
            assertThat(second.steps()).hasSize(2);
        }
    }
}
