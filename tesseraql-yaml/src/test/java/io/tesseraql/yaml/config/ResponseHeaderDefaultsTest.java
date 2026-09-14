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

    /**
     * A control character in a declared default is refused where it is declared
     * (docs/edge-hygiene.md E3): the asset, SSE and MCP surfaces write these values straight to
     * the transport, where a control character hangs the connection. HTAB stays a field value's
     * one permitted control (RFC 9110 section 5.5).
     */
    @Test
    void aControlCharacterInADefaultIsRefusedNamingTheHeader() {
        assertThatThrownBy(() -> defaults(Map.of("X-Frame-Options", "DENY\u0000")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("X-Frame-Options")
                .hasMessageContaining("U+0000");
        assertThatThrownBy(() -> defaults(Map.of("Content-Security-Policy",
                "default-src 'self'\u007F")))
                .hasMessageContaining("U+007F");
        assertThat(defaults(Map.of("X-Note", "a\tb")).headers()).containsEntry("X-Note", "a\tb");
    }

    @Test
    void theDefaultsKeepTheOrderTheyWereDeclaredIn() {
        // The security block every bundled app ships, verbatim. Its four names have exactly eight
        // reachable iteration orders and the authored one is not among them, so this was red on
        // every boot (docs/deterministic-output.md decision 2). The source map is a
        // LinkedHashMap, not a Map.of literal: a literal is salted before the defaults see it,
        // and the test would then never cross the boundary under test.
        Map<String, Object> declared = new LinkedHashMap<>();
        declared.put("Content-Security-Policy", "default-src 'self'");
        declared.put("X-Content-Type-Options", "nosniff");
        declared.put("X-Frame-Options", "DENY");
        declared.put("Referrer-Policy", "no-referrer");

        assertThat(defaults(declared).headers().keySet()).containsExactly(
                "Content-Security-Policy", "X-Content-Type-Options", "X-Frame-Options",
                "Referrer-Policy");
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
