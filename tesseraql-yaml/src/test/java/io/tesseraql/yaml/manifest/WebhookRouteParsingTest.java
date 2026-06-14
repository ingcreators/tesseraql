package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 26 inbound {@code webhook} recipe: an HMAC-verified POST endpoint feeding a
 * SQL pipeline, naming the verifier it authenticates against.
 */
class WebhookRouteParsingTest {

    @Test
    void parsesAWebhookRoute(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path routeDir = dir.resolve("web/hooks");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: partner.events
                kind: route
                recipe: webhook
                webhook:
                  provider: partner
                input:
                  eventId:
                    type: string
                    required: true
                sql:
                  file: insert-event.sql
                  mode: update
                  params:
                    eventId: body.eventId
                response:
                  json:
                    status: 202
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        RouteDefinition route = manifest.routes().get(0).definition();

        assertThat(route.recipe()).isEqualTo("webhook");
        assertThat(route.webhook()).isNotNull();
        assertThat(route.webhook().provider()).isEqualTo("partner");
        assertThat(route.sql().file()).isEqualTo("insert-event.sql");
    }
}
