package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The executable form of Matrix 1 in docs/route-governance-parity.md: every served recipe
 * carries the same governance head, in the same order.
 *
 * <p>This exists because the head used to be six hand-written lists — one per {@code build*}
 * method — and each dropped a different step. File routes lost tenancy and rate limiting, queue
 * consumers and MCP tools lost the audit trail, workflow delegation lost nearly all of it. A
 * review cannot reliably catch a missing line in a list restated six times; compiling each
 * recipe and reading back its processors can.
 *
 * <p>The assertion is on the compiled Camel model rather than on the compiler's source, so a
 * future recipe that forgets to call the applier fails here even if it looks right.
 */
class RecipeGovernanceTest {

    /** Recipes whose compiled route must carry the full governed head, by route id. */
    private static final List<String> GOVERNED_ROUTES = List.of(
            "items.list", // query-json
            "items.create", // command-json
            "items.page", // page
            "items.export", // query-export
            "items.import", // file-import
            "items.dump", // file-export
            "queue.items.consumed",
            "mcp.items.tool",
            // A prompt is a route like its three mcp siblings (docs/prompt-as-recipe.md): it
            // gets the head every recipe gets, which is the whole point of it having one.
            "mcp.prompt.items.brief");

    @Test
    void everyRecipeCarriesTheGovernedHeadInOrder(@TempDir Path dir) throws Exception {
        Map<String, List<String>> compiled = compileAndCollect(dir);

        assertThat(compiled).containsKeys(GOVERNED_ROUTES.toArray(String[]::new));
        for (String routeId : GOVERNED_ROUTES) {
            List<String> steps = compiled.get(routeId);
            assertThat(steps)
                    .as("route '%s' governance head", routeId)
                    .containsSubsequence(RouteCompiler.GOVERNED_STEPS.toArray(String[]::new));
        }
    }

    @Test
    void attachmentRoutesCarryWhatTheyCanAndTheSameLocale(@TempDir Path dir) throws Exception {
        Map<String, List<String>> compiled = compileAndCollect(dir);

        // An attachment has no admission: or input:, so concurrency, lane and audit have nothing to
        // read. Tenancy and locale do apply, and all three routes used to skip tenancy while
        // only upload resolved a locale.
        for (String routeId : List.of("notes.upload", "notes.list", "notes.download")) {
            assertThat(compiled.get(routeId))
                    .as("attachment route '%s'", routeId)
                    .containsSubsequence("RouteTelemetry", "TenantResolution", "LocaleResolution");
        }
    }

    @Test
    void aPageClosesTheIdempotencyRecordItOpens(@TempDir Path dir) throws Exception {
        List<String> page = compileAndCollect(dir).get("items.page");

        // Begin without complete leaves the record IN_PROGRESS, so every retry with the same key
        // conflicts for the whole TTL instead of serving the page.
        assertThat(page).contains("IdempotencyBegin");
        assertThat(page).contains("IdempotencyComplete");
    }

    @Test
    void aToolThatWritesCanEmitToLiveViews(@TempDir Path dir) throws Exception {
        List<String> tool = compileAndCollect(dir).get("mcp.items.write");

        // emit: was accepted on a tool and did nothing: buildMcpTool never added the step, so a
        // model-driven write left every live view watching the same data stale.
        assertThat(tool).contains("TopicEmitProcessor");
    }

    /** Compiles the fixture app and maps each route id to its processors' simple class names. */
    private static Map<String, List<String>> compileAndCollect(Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (DefaultCamelContext context = new DefaultCamelContext()) {
            new RouteCompiler().appName("governance-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }

    /**
     * One route per served recipe, plus an attachment document. Audit, tenancy, a rate limit and
     * a lane are all enabled, because each is conditional on configuration — a fixture without
     * them would assert an empty head and pass against the very bug this guards.
     */
    private static void writeApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tenancy:
                  enabled: true
                  mode: shared-schema
                  resolver:
                    type: header
                    source: X-Tenant-Id

                tesseraql:
                  app:
                    name: governance-test
                  audit:
                    routes:
                      enabled: true
                  lanes:
                    reports:
                      threads: 2
                """);

        String policy = """
                admission:
                  lane: reports
                  rateLimit:
                    requestsPerSecond: 10
                """;

        route(dir, "web/items", "get.yml", """
                version: tesseraql/v1
                id: items.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                %s
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(policy), "list.sql");

        route(dir, "web/items/create", "post.yml", """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                security:
                  auth: public
                %s
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: "true"
                """.formatted(policy), "insert.sql");

        route(dir, "web/items/page", "get.yml", """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: page
                security:
                  auth: public
                %s
                idempotency:
                  required: true
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    template: page.html
                """.formatted(policy), "list.sql");
        Files.writeString(dir.resolve("web/items/page/page.html"), "<p>x</p>\n");

        route(dir, "web/items/export", "get.yml", """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                security:
                  auth: public
                %s
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                export:
                  format: csv
                """.formatted(policy), "list.sql");

        route(dir, "web/items/import", "post.yml", """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import
                security:
                  auth: public
                %s
                import:
                  format: csv
                  columns: [name]
                steps:
                  - id: row
                    sql:
                      file: insert.sql
                """.formatted(policy), "insert.sql");

        route(dir, "web/items/dump", "get.yml", """
                version: tesseraql/v1
                id: items.dump
                kind: route
                recipe: file-export
                security:
                  auth: public
                %s
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: list.sql
                """.formatted(policy), "list.sql");

        route(dir, "consume/items", "consumed.yml", """
                version: tesseraql/v1
                id: items.consumed
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: items.changed
                %s
                input:
                  name: { type: string }
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                """.formatted(policy), "insert.sql");

        route(dir, "mcp", "tool.yml", """
                version: tesseraql/v1
                id: items.tool
                kind: tool
                recipe: query-json
                description: list items
                security:
                  policy: app.read
                %s
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(policy), "list.sql");

        route(dir, "mcp", "write.yml", """
                version: tesseraql/v1
                id: items.write
                kind: tool
                recipe: command-json
                description: add an item
                security:
                  policy: app.write
                %s
                input:
                  name: { type: string }
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                emit:
                  - items.changed
                """.formatted(policy), "insert.sql");

        route(dir, "mcp", "brief.yml", """
                version: tesseraql/v1
                id: items.brief
                kind: prompt
                recipe: prompt-text
                description: brief the model on the items
                security:
                  policy: app.read
                %s
                input:
                  name: { type: string }
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  text:
                    template: brief.txt.tpl
                    model:
                      items: main.rows
                """.formatted(policy), "list.sql");
        Files.writeString(dir.resolve("mcp/brief.txt.tpl"), "[(${items})]\n");

        Files.createDirectories(dir.resolve("attachments"));
        Files.writeString(dir.resolve("attachments/notes.yml"), """
                version: tesseraql/v1
                id: notes
                kind: attachment
                path: /api/notes/{key}/files
                record:
                  entity: notes
                  key: path.key
                security:
                  auth: public
                """);
    }

    private static void route(Path dir, String folder, String document, String yaml, String sql)
            throws Exception {
        Path target = dir.resolve(folder);
        Files.createDirectories(target);
        Files.writeString(target.resolve(document), yaml);
        Files.writeString(target.resolve(sql),
                "insert.sql".equals(sql)
                        ? "insert into items (name) values (/* name */ 'x')\n"
                        : "select id, name from items\n");
    }
}
