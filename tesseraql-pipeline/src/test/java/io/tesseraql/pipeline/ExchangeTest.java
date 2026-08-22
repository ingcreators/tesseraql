package io.tesseraql.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The exchange's two load-bearing contracts (docs/vertx-native.md decision 5, decision 3): the
 * completion drain — the audit row, the permits, the span and the streamed body all ride on it —
 * and the declared conversions, which answer null rather than guess where the old converter
 * registry could find a path nobody wrote down.
 */
class ExchangeTest {

    @Test
    void drainRunsCompletionsInOrderAndOnlyOnce() {
        Exchange exchange = new Exchange(Beans.NONE);
        List<String> ran = new ArrayList<>();
        exchange.addOnCompletion(done -> ran.add("audit"));
        exchange.addOnCompletion(done -> ran.add("permit"));

        exchange.drain();
        exchange.drain();

        assertThat(ran).containsExactly("audit", "permit");
    }

    /** An audit row that cannot be written is not a reason to leak a permit. */
    @Test
    void aFailingCompletionDoesNotStrandTheOnesAfterIt() {
        Exchange exchange = new Exchange(Beans.NONE);
        List<String> ran = new ArrayList<>();
        exchange.addOnCompletion(done -> {
            throw new IllegalStateException("audit store down");
        });
        exchange.addOnCompletion(done -> ran.add("permit"));

        exchange.drain();

        assertThat(ran).containsExactly("permit");
    }

    /** A completion registered mid-drain waits for the next drain; the running one is a
     * snapshot. */
    @Test
    void aCompletionRegisteredDuringDrainDoesNotJoinTheRunningDrain() {
        Exchange exchange = new Exchange(Beans.NONE);
        List<String> ran = new ArrayList<>();
        exchange.addOnCompletion(done -> {
            ran.add("first");
            done.addOnCompletion(late -> ran.add("late"));
        });

        exchange.drain();
        assertThat(ran).containsExactly("first");

        exchange.drain();
        assertThat(ran).containsExactly("first", "late");
    }

    @Test
    void declaredConversionsCoverTextBytesAndStreams() {
        Exchange exchange = new Exchange(Beans.NONE);

        exchange.setBody("text");
        assertThat(exchange.getBody(byte[].class))
                .isEqualTo("text".getBytes(StandardCharsets.UTF_8));

        exchange.setBody("音声".getBytes(StandardCharsets.UTF_8));
        assertThat(exchange.getBody(String.class)).isEqualTo("音声");

        exchange.setBody(new ByteArrayInputStream("streamed".getBytes(StandardCharsets.UTF_8)));
        assertThat(exchange.getBody(String.class)).isEqualTo("streamed");

        exchange.setBody("42");
        assertThat(exchange.getBody(Integer.class)).isEqualTo(42);
    }

    /** A conversion this framework does not perform is a null rather than a guess. */
    @Test
    void anUndeclaredConversionAnswersNull() {
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setBody("not a map");

        assertThat(exchange.getBody(java.util.UUID.class)).isNull();
        exchange.setBody(null);
        assertThat(exchange.getBody(String.class)).isNull();
    }

    @Test
    void propertyFallbackAppliesOnlyWhenUnset() {
        Exchange exchange = new Exchange(Beans.NONE);

        assertThat(exchange.getProperty("limit", 10, Integer.class)).isEqualTo(10);
        exchange.setProperty("limit", 25);
        assertThat(exchange.getProperty("limit", 10, Integer.class)).isEqualTo(25);

        // Setting null removes: the fallback applies again.
        exchange.setProperty("limit", null);
        assertThat(exchange.getProperty("limit", 10, Integer.class)).isEqualTo(10);
    }
}
