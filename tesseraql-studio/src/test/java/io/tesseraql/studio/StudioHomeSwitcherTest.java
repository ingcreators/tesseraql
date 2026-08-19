package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The bundled Studio home is the workshop switcher (docs/studio-shell.md structural
 * decision 2): {@code GET /_tesseraql/studio} renders the members the caller's
 * {@code tql.studio.edit} atoms reach. It rides the app's browser-session default — a
 * switcher that lists grants must know who is asking — and calls the shell's nav provider,
 * which is deny-by-default: no atoms, no entries.
 */
class StudioHomeSwitcherTest {

    private static final String RESOURCE = "tesseraql/apps/studio/web/_tesseraql/studio/get.yml";

    @Test
    void studioHomeIsTheWorkshopSwitcher() throws Exception {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertThat(in).as("bundled %s", RESOURCE).isNotNull();
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        RouteDefinition route = new SimpleYamlParser().parseRoute(yaml, RESOURCE);

        assertThat(route.recipe()).isEqualTo("query-html");
        // No declared auth: the app-level browser default applies — the switcher must know
        // the caller, because what it lists is the caller's own grants.
        assertThat(route.security() == null ? null : route.security().auth()).isNull();
        assertThat(yaml).contains("name: studio.shell.nav")
                .contains("shellPermissions: principal.permissions")
                .contains("template: home.html");
        assertThat(route.response().redirect()).isNull();
    }
}
