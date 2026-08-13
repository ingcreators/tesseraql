package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every declared source is mounted exactly once per compiled route. The unified-sources
 * migration (docs/unified-sources.md) merged the disjoint {@code queries:}/{@code http:} maps
 * into one {@code sources()} map, and two loops that used to iterate different maps quietly
 * began iterating the same one: a command's {@code http:} source was mounted again after the
 * commit — a second partner call whose failure turned a committed write into an error response
 * — and a non-transactional MCP tool executed its whole read pipeline twice per invocation.
 *
 * <p>Like {@link RecipeGovernanceTest}, the assertion reads the compiled Camel model rather
 * than the compiler's source: a repeated SQL read is idempotent, so no behavioral test notices
 * the second mount — counting processors does.
 */
class SourceMountingTest {

    @Test
    void aCommandFetchesItsHttpSourceOnceBeforeTheTransaction(@TempDir Path dir)
            throws Exception {
        List<String> steps = compile(dir).get("orders.create");

        assertThat(count(steps, "HttpSourceProcessor"))
                .as("http sources mounted on the command route")
                .isEqualTo(1);
        assertThat(steps).containsSubsequence("HttpSourceProcessor",
                "TransactionalCommandProcessor");
        // The named query still runs once, after the command, outside its transaction.
        assertThat(count(steps, "NamedQueryBinder")).isEqualTo(1);
        assertThat(steps).containsSubsequence("TransactionalCommandProcessor",
                "NamedQueryBinder");
    }

    @Test
    void aQueryRouteMountsEachSourceOnce(@TempDir Path dir) throws Exception {
        List<String> steps = compile(dir).get("orders.list");

        assertThat(count(steps, "HttpSourceProcessor")).isEqualTo(1);
        assertThat(count(steps, "NamedQueryBinder")).isEqualTo(1);
    }

    @Test
    void aReadToolRunsItsPipelineOncePerInvocation(@TempDir Path dir) throws Exception {
        List<String> steps = compile(dir).get("mcp.orders.lookup");

        // Two SQL sources, one mount each. The trailing loop in buildMcpTool used to mount
        // every source a second time on the non-transactional branch.
        assertThat(count(steps, "NamedQueryBinder")).isEqualTo(2);
    }

    @Test
    void aCommandToolFetchesOnceAndKeepsItsNamedQuery(@TempDir Path dir) throws Exception {
        List<String> steps = compile(dir).get("mcp.orders.record");

        assertThat(count(steps, "HttpSourceProcessor")).isEqualTo(1);
        assertThat(steps).containsSubsequence("HttpSourceProcessor",
                "TransactionalCommandProcessor");
        assertThat(count(steps, "NamedQueryBinder")).isEqualTo(1);
    }

    private static long count(List<String> steps, String name) {
        return steps.stream().filter(name::equals).count();
    }

    /** Compiles the fixture app and maps each route id to its processors' simple class names. */
    private static Map<String, List<String>> compile(Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        Map<String, List<String>> byRoute = new LinkedHashMap<>();
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            context.addRoutes(new RouteCompiler().appName("source-mounting-test")
                    .compile(manifest, false, null));
            for (org.apache.camel.model.RouteDefinition route : context.getRouteDefinitions()) {
                byRoute.put(route.getRouteId(), processorNames(route.getOutputs()));
            }
        }
        return byRoute;
    }

    private static List<String> processorNames(List<ProcessorDefinition<?>> outputs) {
        List<String> names = new ArrayList<>();
        for (ProcessorDefinition<?> output : outputs) {
            if (output instanceof ProcessDefinition process && process.getProcessor() != null) {
                names.add(process.getProcessor().getClass().getSimpleName());
            } else {
                names.add(output.getClass().getSimpleName());
            }
            names.addAll(processorNames(output.getOutputs()));
        }
        return names;
    }

    private static void writeApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: source-mounting-test
                """);

        Path create = dir.resolve("web/api/orders/create");
        Files.createDirectories(create);
        Files.writeString(create.resolve("insert.sql"),
                "insert into orders (partner_code, partner_name)"
                        + " values (/* partnerCode */'P1', /* partnerName */'x')\n");
        Files.writeString(create.resolve("recent.sql"), "select id from orders\n");
        Files.writeString(create.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                security:
                  auth: public
                input:
                  partnerCode: { type: string, required: true }
                steps:
                  - id: header
                    sql:
                      file: insert.sql
                      params:
                        partnerCode: body.partnerCode
                        partnerName: partner.body.name
                sources:
                  partner:
                    http:
                      url: http://localhost:9/partner
                      readOnly: true
                  recent:
                    sql:
                      file: recent.sql
                      mode: query
                response:
                  json:
                    status: 201
                    body:
                      ok: true
                """);

        Path list = dir.resolve("web/api/orders");
        Files.createDirectories(list);
        Files.writeString(list.resolve("list.sql"), "select id from orders\n");
        Files.writeString(list.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                  partner:
                    http:
                      url: http://localhost:9/partner
                response:
                  json:
                    body:
                      data: main.rows
                """);

        Path mcp = dir.resolve("mcp");
        Files.createDirectories(mcp);
        Files.writeString(mcp.resolve("lookup.sql"), "select id from orders\n");
        Files.writeString(mcp.resolve("names.sql"), "select partner_name from orders\n");
        Files.writeString(mcp.resolve("record.sql"),
                "insert into orders (partner_code, partner_name)"
                        + " values (/* partnerCode */'P1', /* partnerName */'x')\n");
        Files.writeString(mcp.resolve("lookup.yml"), """
                version: tesseraql/v1
                id: orders.lookup
                kind: tool
                recipe: query-json
                description: list orders
                security:
                  policy: app.read
                sources:
                  main:
                    sql:
                      file: lookup.sql
                      mode: query
                  names:
                    sql:
                      file: names.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.writeString(mcp.resolve("record.yml"), """
                version: tesseraql/v1
                id: orders.record
                kind: tool
                recipe: command-json
                description: record an order
                security:
                  policy: app.write
                input:
                  partnerCode: { type: string, required: true }
                steps:
                  - id: header
                    sql:
                      file: record.sql
                      params:
                        partnerCode: body.partnerCode
                        partnerName: partner.body.name
                sources:
                  partner:
                    http:
                      url: http://localhost:9/partner
                      readOnly: true
                  recent:
                    sql:
                      file: lookup.sql
                      mode: query
                """);
    }
}
