package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.config.AppConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The one whitelisted key a mounted app's own config contributes (docs/route-defaults.md). */
class SystemAppsConfigTest {

    @Test
    void aMountedAppsResponseHeadersOverlayTheHostConfig() {
        AppConfig main = new AppConfig(Map.of("tesseraql", Map.of(
                "app", Map.of("name", "host"),
                "security", Map.of("responseHeaders", Map.of("X-Frame-Options", "SAMEORIGIN")))),
                name -> null);
        AppConfig own = new AppConfig(Map.of("tesseraql", Map.of("security",
                Map.of("responseHeaders", Map.of("X-Frame-Options", "DENY")))), name -> null);

        AppConfig mounted = SystemApps.withOwnResponseHeaders(main, own);

        // The bundled app owns its pages' header block; everything else stays the host's.
        assertThat(mounted.navigate("tesseraql.security.responseHeaders"))
                .isEqualTo(Map.of("X-Frame-Options", "DENY"));
        assertThat(mounted.getString("tesseraql.app.name")).contains("host");
        // The host tree is untouched (the overlay works on a deep copy).
        assertThat(main.navigate("tesseraql.security.responseHeaders"))
                .isEqualTo(Map.of("X-Frame-Options", "SAMEORIGIN"));
    }

    @Test
    void anythingElseInAMountedConfigStaysInert() {
        AppConfig main = new AppConfig(Map.of("tesseraql", Map.of("datasources",
                Map.of("main", Map.of("jdbcUrl", "jdbc:host")))), name -> null);
        AppConfig own = new AppConfig(Map.of("tesseraql", Map.of(
                "datasources", Map.of("main", Map.of("jdbcUrl", "jdbc:evil")))), name -> null);

        AppConfig mounted = SystemApps.withOwnResponseHeaders(main, own);

        assertThat(mounted).isSameAs(main);
        assertThat(mounted.getString("tesseraql.datasources.main.jdbcUrl"))
                .contains("jdbc:host");
    }
}
