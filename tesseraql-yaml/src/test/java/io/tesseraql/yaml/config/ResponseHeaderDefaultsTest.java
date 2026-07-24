package io.tesseraql.yaml.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResponseHeaderDefaultsTest {

    private static ResponseHeaderDefaults defaults(Map<String, Object> headers) {
        return ResponseHeaderDefaults.from(new AppConfig(Map.of("tesseraql",
                Map.of("security", Map.of("responseHeaders", headers))), name -> null));
    }

    @Test
    void defaultsMergeUnderRouteHeadersPerName() {
        ResponseHeaderDefaults defaults = defaults(new LinkedHashMap<>(Map.of(
                "X-Frame-Options", "DENY", "Referrer-Policy", "no-referrer")));

        Map<String, Object> merged = defaults.mergeUnder(Map.of(
                "Referrer-Policy", "same-origin", "HX-Trigger", "toast"));

        assertThat(merged)
                .containsEntry("X-Frame-Options", "DENY")
                .containsEntry("Referrer-Policy", "same-origin")
                .containsEntry("HX-Trigger", "toast");
    }

    @Test
    void unsetRemovesTheHeaderEntirely() {
        ResponseHeaderDefaults defaults = defaults(Map.of("X-Frame-Options", "DENY"));

        assertThat(defaults.mergeUnder(Map.of("X-Frame-Options", "unset"))).isEmpty();
    }

    @Test
    void absentConfigLeavesRouteHeadersUntouched() {
        ResponseHeaderDefaults defaults = ResponseHeaderDefaults
                .from(new AppConfig(Map.of(), name -> null));

        Map<String, Object> routeHeaders = Map.of("HX-Trigger", "toast");
        assertThat(defaults.isEmpty()).isTrue();
        assertThat(defaults.mergeUnder(routeHeaders)).isSameAs(routeHeaders);
    }

    @Test
    void placeholdersResolveThroughTheAppConfig() {
        ResponseHeaderDefaults defaults = ResponseHeaderDefaults.from(new AppConfig(
                Map.of("tesseraql", Map.of("security", Map.of("responseHeaders",
                        Map.of("Content-Security-Policy",
                                "frame-ancestors ${FRAME_ANCESTORS:'none'}"))),
                        "FRAME_ANCESTORS", "'self'"),
                name -> null));

        // The dotted-path fallback chain: config value wins over the literal default.
        assertThat(defaults.headers())
                .containsEntry("Content-Security-Policy", "frame-ancestors 'self'");
    }

    @Test
    void malformedDeclarationsFailFast() {
        assertThatThrownBy(() -> ResponseHeaderDefaults.from(new AppConfig(
                Map.of("tesseraql", Map.of("security",
                        Map.of("responseHeaders", "nosniff"))),
                name -> null)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("map");

        Map<String, Object> nullValued = new HashMap<>();
        nullValued.put("X-Frame-Options", null);
        assertThatThrownBy(() -> defaults(nullValued))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("no value");
    }
}
