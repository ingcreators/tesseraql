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

    /**
     * The job's resolver is the route's (docs/route-filename-placeholders.md decision 4): an
     * absent value is {@code _}, visibly, where it used to be nothing ({@code orders-.csv} was
     * the silent shape the push lint exists to catch), and a value is folded to a filename
     * component before it names a file.
     */
    @Test
    void anUnresolvedPlaceholderRendersAnUnderscore() {
        assertThat(context().interpolate("orders-{typo}.csv")).isEqualTo("orders-_.csv");
    }

    @Test
    void aValueIsFoldedLikeASplitKey() {
        StepContext context = new StepContext(null, null, null, new StepContext.Invocation(null,
                null, null, Map.of("batch", Map.of("businessDate", "2026-03-31"),
                        "params", Map.of("region", "east/west")),
                null, null, null));
        assertThat(context.interpolate("orders-{params.region}-{batch.businessDate}.csv"))
                .isEqualTo("orders-east_west-2026-03-31.csv");
    }

    @Test
    void aTemplateWithoutPlaceholdersIsReturnedAsIs() {
        assertThat(context().interpolate("orders.csv")).isEqualTo("orders.csv");
    }
}
