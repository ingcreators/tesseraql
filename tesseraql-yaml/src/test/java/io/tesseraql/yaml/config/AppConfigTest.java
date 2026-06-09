package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppConfigTest {

    private static AppConfig config(Map<String, Object> root, Map<String, String> env) {
        return new AppConfig(root, env::get);
    }

    @Test
    void resolvesCrossFileReference() {
        Map<String, Object> root = Map.of(
                "db", Map.of("main", Map.of("url", "jdbc:postgresql://h/db")),
                "tesseraql", Map.of("datasources", Map.of("main", Map.of("jdbcUrl", "${db.main.url}"))));
        AppConfig config = config(root, Map.of());

        assertThat(config.requireString("tesseraql.datasources.main.jdbcUrl"))
                .isEqualTo("jdbc:postgresql://h/db");
    }

    @Test
    void environmentVariableTakesPrecedence() {
        Map<String, Object> root = Map.of("db", Map.of("main", Map.of("username", "${DB_USER:fallback}")));
        assertThat(config(root, Map.of("DB_USER", "real")).requireString("db.main.username"))
                .isEqualTo("real");
        assertThat(config(root, Map.of()).requireString("db.main.username"))
                .isEqualTo("fallback");
    }

    @Test
    void resolvesNestedDefaultPlaceholder() {
        Map<String, Object> root = Map.of("tesseraql", Map.of("app",
                Map.of("work", "${TESSERAQL_WORK_HOME:${TESSERAQL_APP_HOME}/work}")));
        AppConfig config = config(root, Map.of("TESSERAQL_APP_HOME", "/srv/app"));

        assertThat(config.requireString("tesseraql.app.work")).isEqualTo("/srv/app/work");
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertThat(config(Map.of(), Map.of()).getString("nope.missing")).isEmpty();
    }

    @Test
    void unresolvablePlaceholderThrows() {
        Map<String, Object> root = Map.of("x", "${UNDEFINED_NO_DEFAULT}");
        assertThatThrownBy(() -> config(root, Map.of()).requireString("x"))
                .isInstanceOf(TqlException.class);
    }
}
