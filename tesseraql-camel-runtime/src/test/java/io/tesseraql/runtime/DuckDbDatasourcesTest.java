package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The attach conninfo derivation (docs/duckdb.md), including the embedded-db override. */
class DuckDbDatasourcesTest {

    private static AppConfig config() {
        return new AppConfig(Map.of("tesseraql", Map.of("datasources", Map.of(
                "main", Map.of(
                        "jdbcUrl", "jdbc:postgresql://db.example:6543/app",
                        "username", "svc",
                        "password", "s'cret")))));
    }

    @Test
    void derivesConninfoFromTheDeclaredDatasource() {
        assertThat(DuckDbDatasources.conninfo(config(), "main", null))
                .isEqualTo("host='db.example' port=6543 dbname='app' user='svc'"
                        + " password='s\\'cret'");
    }

    @Test
    void theEmbeddedDbOverrideWinsForMain() {
        DataSources.MainDatasourceOverride override = new DataSources.MainDatasourceOverride(
                "jdbc:postgresql://localhost:39471/postgres", "postgres", null);

        assertThat(DuckDbDatasources.conninfo(config(), "main", override))
                .isEqualTo("host='localhost' port=39471 dbname='postgres' user='postgres'");
    }
}
