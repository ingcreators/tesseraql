package io.tesseraql.yaml.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one-line trigger story (docs/jobs.md): shared by the CLI, the symbols contract, and
 * the operations console — one description, three surfaces, zero drift.
 */
class TriggerSpecTest {

    @Test
    void describesEveryTriggerKind() {
        assertThat(TriggerSpec.describe(null)).isEqualTo("on demand");
        assertThat(TriggerSpec.describe(new TriggerSpec(null, null, "extract.orders")))
                .isEqualTo("after extract.orders");
        assertThat(TriggerSpec.describe(new TriggerSpec(
                new TriggerSpec.Schedule("0 0 2 * * ?", null))))
                .isEqualTo("cron 0 0 2 * * ?");
        assertThat(TriggerSpec.describe(new TriggerSpec(
                new TriggerSpec.Schedule(null, "15m"))))
                .isEqualTo("every 15m");
    }

    @Test
    void calendarQualifiersJoinTheStory() {
        assertThat(TriggerSpec.describe(new TriggerSpec(new TriggerSpec.Schedule(
                "0 0 2 * * ?", null, "jp-banking", "last-business-day-of-month", null, null))))
                .isEqualTo("cron 0 0 2 * * ?, calendar jp-banking"
                        + " (lastBusinessDayOfMonth)");
        // A nominal-day rule wins the parenthesis: it is the more specific qualifier.
        assertThat(TriggerSpec.describe(new TriggerSpec(new TriggerSpec.Schedule(
                "0 0 8 * * ?", null, "jp-banking", null, 5, "next-business-day"))))
                .isEqualTo("cron 0 0 8 * * ?, calendar jp-banking (day 5)");
    }
}
