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
                sql:
                  file: list.sql
                publish:
                  channel: events
                  topic: report.viewed
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1010") && f.isError()
                && f.source().contains("get.yml"));
    }
}
