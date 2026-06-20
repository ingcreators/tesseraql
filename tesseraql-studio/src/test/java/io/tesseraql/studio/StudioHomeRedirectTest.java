package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The bundled Studio home alias: a public {@code GET /_tesseraql/studio} that 302-redirects to the
 * real UI landing at {@code /_tesseraql/studio/ui}, so the bare, documented path resolves instead of
 * 404ing. The alias is public, but the {@code /ui} target keeps its own {@code auth: bearer}.
 */
class StudioHomeRedirectTest {

    private static final String RESOURCE = "tesseraql/apps/studio/web/_tesseraql/studio/get.yml";

    @Test
    void studioHomeRedirectsToTheUiLandingPage() throws Exception {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as("bundled %s", RESOURCE).isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        RouteDefinition route = new SimpleYamlParser().parseRoute(yaml, RESOURCE);

        assertThat(route.recipe()).isEqualTo("query-json");
        // Public so the alias itself never demands a token; the /ui target enforces auth: bearer.
        assertThat(route.security().auth()).isEqualTo("public");
        assertThat(route.response().redirect()).isNotNull();
        assertThat(route.response().redirect().location()).isEqualTo("/_tesseraql/studio/ui");
        assertThat(route.response().redirect().effectiveStatus()).isEqualTo(302);
    }
}
