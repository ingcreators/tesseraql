package io.tesseraql.core.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Who a PostgreSQL connection says it is (docs/connection-liveness.md decision 1): the labels, and
 * the {@code application_name} PostgreSQL keeps — printable ASCII, at most 63 bytes.
 */
class PostgresPropertiesTest {

    @Test
    void aPoolIsLabelledByItsApplicationAndItsPool() {
        assertThat(PostgresProperties.poolLabel("orders", "tesseraql-main"))
                .isEqualTo("tesseraql/orders/main");
        assertThat(PostgresProperties.poolLabel("orders", "tesseraql-main-jobs"))
                .isEqualTo("tesseraql/orders/main-jobs");
        assertThat(PostgresProperties.poolLabel("orders", "tesseraql-tenant-acme"))
                .isEqualTo("tesseraql/orders/tenant-acme");
        assertThat(PostgresProperties.poolLabel(null, "tesseraql-stack-framework"))
                .as("a pool the stack owns names no application")
                .isEqualTo("tesseraql/stack-framework");
        assertThat(PostgresProperties.jobRunLabel("orders")).isEqualTo("tesseraql/orders/job-run");
    }

    @Test
    void aCharacterOutsidePrintableAsciiIsPercentEncodedAsUtf8() {
        assertThat(PostgresProperties.applicationName("tesseraql/受注/main"))
                .isEqualTo("tesseraql/%E5%8F%97%E6%B3%A8/main");
    }

    @Test
    void aLongLabelIsCutAt63BytesAtACharacterBoundary() {
        assertThat(PostgresProperties.applicationName("x".repeat(80))).hasSize(63);

        // 60 bytes, then a character whose escape is nine: it does not fit, and is not split.
        String cut = PostgresProperties.applicationName("tesseraql/" + "a".repeat(50) + "受");
        assertThat(cut).isEqualTo("tesseraql/" + "a".repeat(50));
    }

    @Test
    void onlyAPostgresqlUrlIsGivenTheProperty() {
        Map<String, String> given = new LinkedHashMap<>();
        PostgresProperties.apply("jdbc:h2:mem:x", "tesseraql/orders/main", given::put);
        PostgresProperties.apply("jdbc:duckdb:", "tesseraql/orders/main", given::put);
        assertThat(given).isEmpty();

        PostgresProperties.apply("jdbc:postgresql://db:5432/app", "tesseraql/orders/main",
                given::put);
        assertThat(given).containsExactly(Map.entry("ApplicationName", "tesseraql/orders/main"));
    }
}
