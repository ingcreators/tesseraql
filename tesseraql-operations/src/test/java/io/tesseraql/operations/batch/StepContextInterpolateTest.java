package io.tesseraql.operations.batch;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The one placeholder a job never resolves: {@code {key}} belongs to the split export, which
 * replaces it once per group. Every other placeholder keeps today's rule - resolved from the
 * context, or empty. Before this, {@code {key}} was erased with the rest and a split step could
 * not run.
 */
class StepContextInterpolateTest {

    private static StepContext context() {
        return new StepContext(null, null, null, new StepContext.Invocation(null, null, null,
                Map.of("batch", Map.of("businessDate", "2026-03-31")), null, null, null));
    }

    @Test
    void theKeyPlaceholderPassesThroughForTheSplitExport() {
        assertThat(context().interpolate("orders-{batch.businessDate}-{key}.csv"))
                .isEqualTo("orders-2026-03-31-{key}.csv");
    }

    @Test
    void anUnresolvedPlaceholderStillRendersEmpty() {
        assertThat(context().interpolate("orders-{typo}.csv")).isEqualTo("orders-.csv");
    }

    @Test
    void aTemplateWithoutPlaceholdersIsReturnedAsIs() {
        assertThat(context().interpolate("orders.csv")).isEqualTo("orders.csv");
    }
}
