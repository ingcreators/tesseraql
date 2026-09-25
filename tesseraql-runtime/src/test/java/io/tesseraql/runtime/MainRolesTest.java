package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A role pool this runtime would never use is refused before any pool connects
 * (docs/capacity-defaults.md decision 5): read and ignored, it would look like isolation that was
 * never there.
 */
class MainRolesTest {

    private static final Map<String, Object> JOB_POOL = Map.of("jobPool",
            Map.of("maximumPoolSize", 3));

    @Test
    void aRolePoolUnderAnotherDatasourceIsRefused() {
        AppConfig config = datasources(Map.of(
                "main", Map.of("jdbcUrl", "jdbc:postgresql://unreachable.invalid/app"),
                "erp", Map.of("jdbcUrl", "jdbc:postgresql://unreachable.invalid/erp",
                        "fileTransferPool", Map.of("maximumPoolSize", 2))));

        assertThatThrownBy(() -> MainRoles.refuseMisplaced(config))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1115")
                .hasMessageContaining("tesseraql.datasources.erp declares fileTransferPool");
    }

    /** The refusal comes before a connection is attempted, so a typo never waits on a network. */
    @Test
    void theRefusalComesBeforeAnyPoolConnects() {
        AppConfig config = datasources(Map.of(
                "main", Map.of("jdbcUrl", "jdbc:postgresql://unreachable.invalid/app"),
                "erp", Map.of("jdbcUrl", "jdbc:postgresql://unreachable.invalid/erp",
                        "jobPool", Map.of("maximumPoolSize", 2))));

        assertThatThrownBy(() -> DataSources.createAll(config))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1115");
    }

    @Test
    void aRolePoolOnADuckDbMainIsRefused() {
        Map<String, Object> main = new java.util.LinkedHashMap<>(JOB_POOL);
        main.put("dialect", "duckdb");
        main.put("jdbcUrl", "jdbc:duckdb:");

        assertThatThrownBy(() -> MainRoles.refuseMisplaced(datasources(Map.of("main", main))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1115")
                .hasMessageContaining("duckdb");
    }

    @Test
    void rolePoolsUnderMainAreWelcome() {
        Map<String, Object> main = new java.util.LinkedHashMap<>(JOB_POOL);
        main.put("jdbcUrl", "jdbc:postgresql://unreachable.invalid/app");

        assertThatCode(() -> MainRoles.refuseMisplaced(datasources(Map.of("main", main))))
                .doesNotThrowAnyException();
    }

    private static AppConfig datasources(Map<String, Object> datasources) {
        return new AppConfig(Map.of("tesseraql", Map.of("datasources", datasources)));
    }
}
