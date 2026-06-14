package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 27 messaging blocks: a {@code publish:} block on a command route under
 * {@code web/}, and a {@code queue-consume} route under {@code consume/} loaded into the manifest's
 * consumer collection rather than its HTTP routes.
 */
class MessagingRouteParsingTest {

    @Test
    void parsesAPublishBlockOnACommandRoute(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                sql:
                  file: insert-order.sql
                  mode: update
                  params:
                    orderId: body.orderId
                publish:
                  channel: events
                  topic: orders.created
                  key: body.orderId
                  payload:
                    orderId: body.orderId
                    total: body.total
                """);
        Files.writeString(routeDir.resolve("insert-order.sql"),
                "insert into orders (id) values (/* orderId */ 'x')\n");

        AppManifest manifest = new ManifestLoader().load(dir);
        RouteDefinition route = manifest.routes().get(0).definition();

        assertThat(route.publish()).isNotNull();
        assertThat(route.publish().channel()).isEqualTo("events");
        assertThat(route.publish().topic()).isEqualTo("orders.created");
        assertThat(route.publish().key()).isEqualTo("body.orderId");
        assertThat(route.publish().payload()).containsEntry("total", "body.total");
    }

    @Test
    void parsesAQueueConsumeRouteIntoTheConsumerCollection(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path consumeDir = dir.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.created
                  idempotencyKey: body.orderId
                sql:
                  file: project-order.sql
                  mode: update
                  params:
                    orderId: body.orderId
                """);
        Files.writeString(consumeDir.resolve("project-order.sql"),
                "insert into projected (id) values (/* orderId */ 'x')\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        // A consumer is not an HTTP route — it loads into consumers(), not routes().
        assertThat(manifest.routes()).isEmpty();
        assertThat(manifest.consumers()).hasSize(1);
        RouteDefinition consumer = manifest.consumers().get(0).definition();
        assertThat(consumer.recipe()).isEqualTo("queue-consume");
        assertThat(consumer.consume()).isNotNull();
        assertThat(consumer.consume().channel()).isEqualTo("events");
        assertThat(consumer.consume().topic()).isEqualTo("orders.created");
        assertThat(consumer.consume().idempotencyKey()).isEqualTo("body.orderId");
    }
}
