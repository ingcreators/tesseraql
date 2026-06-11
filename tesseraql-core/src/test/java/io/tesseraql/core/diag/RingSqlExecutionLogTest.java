package io.tesseraql.core.diag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RingSqlExecutionLogTest {

    @Test
    void keepsOnlyExecutionsOverThreshold() {
        RingSqlExecutionLog log = new RingSqlExecutionLog(10, 100);
        log.record(new SqlExecution("fast.sql", "query", 50, 1, 0));
        log.record(new SqlExecution("slow.sql", "query", 150, 2, 0));

        assertThat(log.recent()).singleElement()
                .extracting(SqlExecution::sqlId).isEqualTo("slow.sql");
    }

    @Test
    void boundedRingDropsOldestAndReturnsMostRecentFirst() {
        RingSqlExecutionLog log = new RingSqlExecutionLog(2, 0);
        log.record(new SqlExecution("a.sql", "query", 10, 0, 0));
        log.record(new SqlExecution("b.sql", "query", 10, 0, 0));
        log.record(new SqlExecution("c.sql", "query", 10, 0, 0));

        assertThat(log.recent()).extracting(SqlExecution::sqlId).containsExactly("c.sql", "b.sql");
    }
}
