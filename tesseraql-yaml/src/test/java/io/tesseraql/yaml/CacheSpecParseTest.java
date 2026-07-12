package io.tesseraql.yaml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** cache: binds into the route model (docs/response-shaping.md, "HTTP caching"). */
class CacheSpecParseTest {

    @Test
    void cacheBlockBindsWithDefaults() {
        var def = new SimpleYamlParser().parseRoute("""
                version: tesseraql/v1
                id: t
                kind: route
                recipe: query-json
                cache:
                  maxAge: 30s
                  visibility: public
                  staleWhileRevalidate: 60s
                sql:
                  file: x.sql
                response:
                  json:
                    status: 200
                """, "get.yml");
        assertThat(def.cache()).isNotNull();
        assertThat(def.cache().maxAge()).isEqualTo("30s");
        assertThat(def.cache().effectiveVisibility()).isEqualTo("public");
        assertThat(def.cache().etagEnabled()).isTrue();
    }
}
