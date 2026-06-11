package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses the Phase 18 transactional-write declarations: ordered {@code steps:}, per-step
 * {@code keys:}/{@code expect:}, managed {@code sequence:} steps, and {@code errors.constraints}.
 */
class CommandStepsParsingTest {

    @Test
    void parsesStepsExpectKeysSequenceAndConstraintMapping(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: orders
                """);
        Path routeDir = dir.resolve("web/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                steps:
                  orderNo:
                    sequence: order-number
                  header:
                    file: insert-order.sql
                    mode: update
                    keys: [id]
                    params:
                      orderNo: steps.orderNo.value
                      customerId: body.customerId
                  lines:
                    file: insert-lines.sql
                    mode: update
                    params:
                      orderId: steps.header.keys.id
                      lines: body.lines
                  bump:
                    file: bump-version.sql
                    mode: update
                    expect:
                      rows: 1
                      onMismatch: conflict
                errors:
                  constraints:
                    orders_customer_fk:
                      field: customerId
                      code: unknown-customer
                response:
                  json:
                    status: 201
                    body:
                      orderId: steps.header.keys.id
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        RouteDefinition route = manifest.routes().get(0).definition();

        // Steps keep their authored order; each carries its own binding declaration.
        assertThat(route.steps().keySet())
                .containsExactly("orderNo", "header", "lines", "bump");
        assertThat(route.steps().get("orderNo").isSequence()).isTrue();
        assertThat(route.steps().get("orderNo").sequence()).isEqualTo("order-number");
        assertThat(route.steps().get("header").keys()).containsExactly("id");
        assertThat(route.steps().get("header").params())
                .containsEntry("orderNo", "steps.orderNo.value");
        assertThat(route.steps().get("lines").params())
                .containsEntry("orderId", "steps.header.keys.id");
        assertThat(route.steps().get("bump").expect().rows()).isEqualTo(1);
        assertThat(route.steps().get("bump").expect().effectiveOnMismatch()).isEqualTo("conflict");

        assertThat(route.errors().constraints())
                .containsKey("orders_customer_fk");
        assertThat(route.errors().constraints().get("orders_customer_fk").field())
                .isEqualTo("customerId");
        assertThat(route.errors().constraints().get("orders_customer_fk").code())
                .isEqualTo("unknown-customer");
    }

    @Test
    void expectDefaultsToConflictOnMismatch(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Path routeDir = dir.resolve("web/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("put.yml"), """
                version: tesseraql/v1
                id: orders.update
                kind: route
                recipe: command-json
                sql:
                  file: update-order.sql
                  mode: update
                  expect:
                    rows: 1
                response:
                  json:
                    status: 200
                    body:
                      affected: sql.affectedRows
                """);

        AppManifest manifest = new ManifestLoader().load(dir);
        RouteDefinition route = manifest.routes().get(0).definition();

        assertThat(route.steps()).isEmpty();
        assertThat(route.sql().expect().rows()).isEqualTo(1);
        assertThat(route.sql().expect().effectiveOnMismatch()).isEqualTo("conflict");
    }
}
