package io.tesseraql.scim.routes;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.core.sql.ContractStatement;
import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SCIM contract SQL is bounded by the key that already bounds route SQL
 * (docs/contract-sql-execution.md structural decision 3): the same
 * {@code tesseraql.sql.timeoutSeconds}, not a second key to explain and to forget.
 */
class ScimRuntimeExtensionTest {

    @Test
    void readsTheBoundFromTheKeyThatBoundsRouteSql() {
        AppConfig config = new AppConfig(Map.of("tesseraql",
                Map.of("sql", Map.of("timeoutSeconds", 5))));

        assertThat(ScimRuntimeExtension.sqlTimeoutSeconds(config)).isEqualTo(5);
    }

    @Test
    void anUnsetKeyIsTheSameThirtySecondsARouteDefaultsTo() {
        assertThat(ScimRuntimeExtension.sqlTimeoutSeconds(new AppConfig(Map.of())))
                .isEqualTo(ContractStatement.DEFAULT_TIMEOUT_SECONDS);
    }

    @Test
    void declaredKeysReadAsAListOrACommaString() {
        assertThat(ScimRuntimeExtension.readKeys(java.util.List.of("id"))).containsExactly("id");
        assertThat(ScimRuntimeExtension.readKeys("id, tenant_id"))
                .containsExactly("id", "tenant_id");
        assertThat(ScimRuntimeExtension.readKeys(null)).isEmpty();
    }
}
