package io.tesseraql.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** lock: binds into the route model (docs/edit-conflict.md decision 1). */
class RouteLockParseTest {

    private static final String UPDATE_ROUTE = """
            version: tesseraql/v1
            id: items.update
            kind: route
            recipe: command-json
            %s
            steps:
              - id: main
                sql:
                  file: update.sql
            response:
              json:
                status: 200
            """;

    @Test
    void theBareFormIsSugarForAnOpaqueLockOnThatColumn() {
        var def = new SimpleYamlParser().parseRoute(UPDATE_ROUTE.formatted("lock: version"),
                "post.yml");
        assertThat(def.lock().column()).isEqualTo("version");
        assertThat(def.lock().type()).isNull();
    }

    @Test
    void theBlockFormAlsoNamesTheColumnType() {
        // A form value always arrives as a string, so a numeric lock column needs its type
        // declared or the comparison cannot be made (docs/edit-conflict.md decision 4).
        var def = new SimpleYamlParser().parseRoute(
                UPDATE_ROUTE.formatted("lock: { column: version, type: integer }"), "post.yml");
        assertThat(def.lock().column()).isEqualTo("version");
        assertThat(def.lock().type()).isEqualTo("integer");
    }

    @Test
    void anUndeclaredLockIsNull() {
        // A route without the declaration behaves exactly as it does today: nothing is implied.
        var def = new SimpleYamlParser().parseRoute(UPDATE_ROUTE.formatted("input: {}"),
                "post.yml");
        assertThat(def.lock()).isNull();
    }

    @Test
    void aBlankLockIsCarriedRatherThanCoercedToNull() {
        // Left un-normalized on purpose, so the compile-time identifier check refuses it loudly
        // instead of the model quietly turning "declared but empty" into "not declared".
        var def = new SimpleYamlParser().parseRoute(UPDATE_ROUTE.formatted("lock: ''"),
                "post.yml");
        assertThat(def.lock().column()).isEmpty();
    }
}
