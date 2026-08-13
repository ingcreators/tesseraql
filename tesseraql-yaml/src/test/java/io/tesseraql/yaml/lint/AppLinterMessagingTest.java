package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints the Phase 27 messaging blocks: publish channels, queue-consume routes, block misuse. */
class AppLinterMessagingTest {

    @Test
    void flagsAQueueConsumeRouteOnAnUnconfiguredChannel(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  messaging:
                    channels:
                      events:
                        transport: pg-notify
                """);
        Path consumeDir = dir.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("project-order.sql"),
                "insert into projected (id) values (/* orderId */ 'x')\n");
        Files.writeString(consumeDir.resolve("clean.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.created
                  idempotencyKey: body.orderId
                steps:
                  - id: main
                    sql:
                      file: project-order.sql
                      mode: update
                      params:
                        orderId: body.orderId
                """);
        Files.writeString(consumeDir.resolve("bad.yml"), """
                version: tesseraql/v1
                id: orders.bad
                kind: route
                recipe: queue-consume
                consume:
                  channel: ghost
                  topic: orders.created
                steps:
                  - id: main
                    sql:
                      file: project-order.sql
                      mode: update
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SEC-4090") && f.isError()
                && f.source().contains("bad.yml"));
        assertThat(findings).noneMatch(f -> f.source().contains("clean.yml"));
    }

    @Test
    void flagsAPublishBlockOnANonCommandRecipe(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  messaging:
                    channels:
                      events:
                        transport: pg-notify
                """);
        Path routeDir = dir.resolve("web/api/report");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("list.sql"), "select 1\n");
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: report.list
                kind: route
                recipe: query-json
                publish:
                  channel: events
                  topic: report.viewed
                sources:
                  main:
                    sql:
                      file: list.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1010") && f.isError()
                && f.source().contains("get.yml"));
    }

    /**
     * A consumer's pipeline is its {@code steps:} — {@code sources.main} used to satisfy the
     * pipeline check while the compiled route refused an empty {@code steps:}, so the document
     * passed lint and failed at startup.
     */
    @Test
    void aConsumerWithOnlyASourceHasNoPipeline(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path consumeDir = dir.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("project.sql"), "select 1\n");
        Files.writeString(consumeDir.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.created
                sources:
                  main:
                    sql:
                      file: project.sql
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1009") && f.isError()
                && f.message().contains("steps: pipeline"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1051") && f.isError());
    }

    /** A source beside a real pipeline still compiles to nothing, and is refused for it. */
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

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1051") && f.isError()
                && f.message().contains("compiles to nothing"));
    }

    /**
     * {@code publish:} rides the command transaction, so a command-json route declaring it with
     * no {@code steps:} pipeline has nowhere to put it — the compiled route refuses to start,
     * and the lint says so at authoring time.
     */
    @Test
    void aPublishWithoutAPipelineIsFlagged(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("insert.sql"), "select 1\n");
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
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

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1052") && f.isError()
                && f.message().contains("ride the command transaction"));
    }

    private static void writeConfig(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  messaging:
                    channels:
                      events:
                        transport: pg-notify
                """);
    }
}
