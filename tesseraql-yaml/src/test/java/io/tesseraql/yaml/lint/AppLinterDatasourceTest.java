package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints the Phase 53 {@code datasource:} surface: TQL-YAML-1035/1036/1037. */
class AppLinterDatasourceTest {

    private static void writeConfig(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    reporting:
                      jdbcUrl: jdbc:postgresql://localhost/reporting
                """);
    }

    @Test
    void acceptsADeclaredConnectorAndFlagsAnUnknownOne(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/sales");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("summary.sql"), "select 1\n");
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sales.summary
                kind: route
                recipe: query-json
                datasource: reporting
                sql:
                  file: summary.sql
                  mode: query
                """);
        Path ghostDir = dir.resolve("web/api/ghost");
        Files.createDirectories(ghostDir);
        Files.writeString(ghostDir.resolve("summary.sql"), "select 1\n");
        Files.writeString(ghostDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sales.ghost
                kind: route
                recipe: query-json
                datasource: warehouse
                sql:
                  file: summary.sql
                  mode: query
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1035") && f.isError()
                && f.source().contains("ghost/get.yml"));
        assertThat(findings).noneMatch(f -> f.source().contains("sales/get.yml"));
    }

    @Test
    void flagsAMainAnchoredFeatureOnANonMainTransaction(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("insert.sql"),
                "insert into orders (id) values (/* orderId */ 'x')\n");
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                datasource: reporting
                input:
                  orderId: { type: string, required: true }
                sql:
                  file: insert.sql
                  mode: update
                  params:
                    orderId: body.orderId
                publish:
                  channel: events
                  topic: orders.created
                  payload:
                    orderId: body.orderId
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1036") && f.isError()
                && f.source().contains("post.yml"));
    }

    @Test
    void acceptsAPlainSqlProjectionConsumerOnANamedConnector(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    reporting:
                      jdbcUrl: jdbc:postgresql://localhost/reporting
                  messaging:
                    channels:
                      events:
                        transport: pg-notify
                """);
        Path consumeDir = dir.resolve("consume/orders");
        Files.createDirectories(consumeDir);
        Files.writeString(consumeDir.resolve("upsert.sql"),
                "insert into order_projection (id) values (/* orderId */ 'x')\n");
        Files.writeString(consumeDir.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                datasource: reporting
                consume:
                  channel: events
                  topic: orders.created
                  idempotencyKey: body.orderId
                input:
                  orderId: { type: string, required: true }
                sql:
                  file: upsert.sql
                  mode: update
                  params:
                    orderId: body.orderId
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(f -> f.code().startsWith("TQL-YAML-103"));
    }

    @Test
    void flagsAPerStepConnectorInsideATransactionalPipeline(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("insert.sql"),
                "insert into orders (id) values (/* orderId */ 'x')\n");
        Files.writeString(routeDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                input:
                  orderId: { type: string, required: true }
                steps:
                  insert:
                    file: insert.sql
                    mode: update
                    datasource: reporting
                    params:
                      orderId: body.orderId
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1037") && f.isError()
                && f.source().contains("post.yml"));
    }

    @Test
    void acceptsANamedQueryOverrideAndChecksItsName(@TempDir Path dir) throws Exception {
        writeConfig(dir);
        Path routeDir = dir.resolve("web/api/dashboard");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("open.sql"), "select 1\n");
        Files.writeString(routeDir.resolve("turnover.sql"), "select 2\n");
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: dashboard.view
                kind: route
                recipe: query-json
                sql:
                  file: open.sql
                  mode: query
                queries:
                  turnover:
                    file: turnover.sql
                    mode: query
                    datasource: reporting
                  ghost:
                    file: turnover.sql
                    mode: query
                    datasource: warehouse
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1035"))
                .hasSize(1);
    }
}
