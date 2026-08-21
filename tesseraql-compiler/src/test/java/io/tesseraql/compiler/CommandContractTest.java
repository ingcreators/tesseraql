package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The compiled contract of the command surfaces: the pipeline is the {@code steps:} array, and
 * a declaration that cannot take effect is refused rather than compiled to nothing. The lints
 * ({@code TQL-YAML-1051}, {@code TQL-YAML-1052}) say the same at authoring time; these are the
 * backstops for an app that reaches startup without them.
 */
class CommandContractTest {

    /** A queue consumer mounts no sources — a declared one is refused, not silently dropped. */
    @Test
    void aConsumerRefusesADeclaredSource(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path consumeDir = dir.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("apply.sql"),
                "insert into projected (id) values (/* orderId */ 'x')\n");
        Files.writeString(consumeDir.resolve("lookup.sql"), "select 1\n");
        Files.writeString(consumeDir.resolve("apply.yml"), """
                version: tesseraql/v1
                id: orders.apply
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.created
                sources:
                  lookup:
                    sql:
                      file: lookup.sql
                steps:
                  - id: main
                    sql:
                      file: apply.sql
                      mode: update
                      params:
                        orderId: body.orderId
                """);

        assertThatThrownBy(() -> compile(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("mounts no sources:");
    }

    /**
     * {@code publish:} is a transactional-outbox write exactly like {@code notify:} — but it
     * was missing from {@code usesTransactionalCommand}, so a command with {@code publish:} and
     * no transactional step compiled down the read path and the publish was silently dropped
     * (the shape the messaging cookbook itself used to teach). It now forces the command
     * pipeline, whose processor refuses the missing {@code steps:} out loud.
     */
    @Test
    void aPublishWithoutAPipelineIsRefusedNotDropped(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("insert.sql"), "select 1\n");
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  orderId: { type: string, required: true }
                sources:
                  main:
                    sql:
                      file: insert.sql
                      mode: update
                publish:
                  channel: events
                  topic: orders.created
                response:
                  json:
                    status: 201
                    body:
                      ok: true
                """);

        assertThatThrownBy(() -> compile(dir))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("needs a steps: declaration");
    }

    private static void compile(Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("command-contract-test")
                    .compile(context, manifest, false, null);
        }
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: command-contract-test
                  messaging:
                    channels:
                      events:
                        transport: pg-notify
                """);
    }
}
