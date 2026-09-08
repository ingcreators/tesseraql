package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.binding.RouteTelemetry;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * <p>The assertion is on the compiled pipeline rather than on the compiler's source, so a
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
            "mcp.prompt.items.brief",
            // A resource and a ui resource are reads like any other. Both heads were written by
            // hand and had drifted out of the applier: no audit row, and the access log wired
            // off by a convenience constructor that has since been deleted.
            "mcp.resource.items.context",
            "mcp.ui.items.board");

    @Test
    void everyRecipeCarriesTheGovernedHeadInOrder(@TempDir Path dir) throws Exception {
        Map<String, List<String>> compiled = compileAndCollect(dir);

        assertThat(compiled).containsKeys(GOVERNED_ROUTES.toArray(String[]::new));
        for (String routeId : GOVERNED_ROUTES) {
            List<String> steps = compiled.get(routeId);
            // Each once, in order — not containsSubsequence, which any superset satisfies.
            // Every governed step class has exactly one construction site in the compiler, so a
            // route carrying two of them is as wrong as one carrying none, and a subsequence
            // assertion is green on both.
            assertThat(steps.stream().filter(RouteCompiler.GOVERNED_STEPS::contains).toList())
                    .as("route '%s' governance head", routeId)
                    .containsExactlyElementsOf(RouteCompiler.GOVERNED_STEPS);
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

    /**
     * The two facts a step's class name cannot show, for the two routes that had them wrong.
     *
     * <p>{@code stepsById} records {@code getClass().getSimpleName()}, so a head assertion sees
     * that a {@code RouteTelemetry} is present and nothing about how it was built. Both of this
     * slice's defects lived in those arguments: the method label the audit row is written with,
     * and the access-log flag the deleted four-argument constructor pinned to false.
     */
    @Test
    void theMcpReadHeadsCarryTheirLabelAndTheAccessLog(@TempDir Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("governance-test")
                    .compile(context, manifest, false, null);

            for (Map.Entry<String, String> route : Map.of(
                    "mcp.resource.items.context", "MCP-RESOURCE",
                    "mcp.ui.items.board", "MCP-UI").entrySet()) {
                List<RouteTelemetry> telemetry = CompiledPipelines.steps(context, route.getKey(),
                        RouteTelemetry.class);

                assertThat(telemetry).as("route '%s' telemetry steps", route.getKey()).hasSize(1);
                assertThat(telemetry.get(0).method())
                        .as("route '%s' method label", route.getKey())
                        .isEqualTo(route.getValue());
                assertThat(telemetry.get(0).accessLog())
                        .as("route '%s' writes the access-log line the fixture enables",
                                route.getKey())
                        .isTrue();
            }
        }
    }

    /** Compiles the fixture app and maps each route id to its processors' simple class names. */
    private static Map<String, List<String>> compileAndCollect(Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("governance-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }

    /**
     * One route per served recipe, plus an attachment document. Audit, tenancy, a rate limit, a
     * lane and the access log are all enabled, because each is conditional on configuration — a
     * fixture without them would assert an empty head and pass against the very bug this guards.
     *
     * <p>The access log is the fifth, and it was the one missing: the two MCP read heads had it
     * hard-wired off, and no fixture here turned it on, so nothing could see the difference.
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
                  logging:
                    accessLog: true
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

        // A resource and a ui resource. uri:, description: and the absence of input: are LINT
        // rules (ResourceRules, UiResourceRules) and this test runs no linter — compileAndCollect
        // is ManifestLoader.load followed by RouteCompiler.compile. They are declared because
        // they are what a reader recognises as these documents, not because anything here
        // enforces them.
        route(dir, "mcp", "context.yml", """
                version: tesseraql/v1
                id: items.context
                kind: resource
                recipe: query-json
                uri: tesseraql://items/context
                description: the items an agent is working against
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

        route(dir, "mcp", "board.yml", """
                version: tesseraql/v1
                id: items.board
                kind: ui
                recipe: query-html
                uri: ui://items/board
                description: the items board an agent can render
                security:
                  policy: app.read
                %s
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                response:
                  html:
                    template: board.html
                """.formatted(policy), "list.sql");
        // The template must exist on disk: TemplateResolution throws at COMPILE time, not on the
        // first request.
        Files.writeString(dir.resolve("mcp/board.html"), "<ul></ul>\n");

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
