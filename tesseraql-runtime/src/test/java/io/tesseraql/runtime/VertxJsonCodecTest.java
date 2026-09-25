package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * One Jackson line on the runtime (docs/jackson-3.md decision 12). vertx-core's codec factory
 * takes Jackson 2 whenever it is on the classpath, and vertx-core brought it; with Jackson 2
 * excluded, Vert.x's own JSON — {@code JsonObject}, the event bus, {@code Json.encode} — runs on
 * the same Jackson 3 as the framework. A Jackson 2 back on the runtime classpath turns the first
 * assertion red (the footprint rules refuse it at build time as well).
 */
class VertxJsonCodecTest {

    @Test
    void vertxEncodesAndDecodesThroughJackson3() {
        assertThat(Json.CODEC.getClass().getName())
                .startsWith("io.vertx.core.json.jackson.v3.");
        JsonObject decoded = new JsonObject("{\"name\":\"café\",\"n\":1,\"list\":[true]}");
        assertThat(decoded.getString("name")).isEqualTo("café");
        assertThat(decoded.getInteger("n")).isEqualTo(1);
        assertThat(decoded.getJsonArray("list").getBoolean(0)).isTrue();
        assertThat(Json.encode(decoded)).isEqualTo("{\"name\":\"café\",\"n\":1,\"list\":[true]}");
    }

    @Test
    void theJackson2ClassesAreNotOnTheRuntime() {
        assertThatThrownBy(() -> Class.forName("com.fasterxml.jackson.core.JsonFactory"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
