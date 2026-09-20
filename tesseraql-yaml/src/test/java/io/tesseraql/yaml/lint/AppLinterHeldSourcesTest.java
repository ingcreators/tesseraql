package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A source's {@code cache:} where nothing can be held (docs/caching.md decision 6) —
 * {@code TQL-YAML-1077}, one arm each, and the legal shape lints clean; and the widened
 * {@code TQL-FIELD-4620}: a command's {@code invalidates:} naming a table a held source reads
 * drops something.
 */
class AppLinterHeldSourcesTest {

    private static final String CONFIG = """
            tesseraql:
              app:
                name: t
              http:
                outbound:
                  allowedHosts:
                    - partners.test
            """;

    private static void writeRoute(Path dir, String name, String recipe, String body)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), CONFIG);
        Path route = Files.createDirectories(dir.resolve("web/" + name));
        Files.writeString(route.resolve("orders.sql"), "select 1 as id\n");
        Files.writeString(route.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(route.resolve("partners.sql"),
                "select code, name from partners where code in /* keys */(1)\n");
        Files.writeString(route.resolve(recipe.startsWith("command") ? "post.yml" : "get.yml"),
                """
                        version: tesseraql/v1
                        id: %s.route
                        kind: route
                        recipe: %s
                        security:
                          auth: public
                        %s
                        """.formatted(name, recipe, body));
    }

    private static List<LintFinding> held(List<LintFinding> findings) {
        return findings.stream().filter(f -> "TQL-YAML-1077".equals(f.code())).toList();
    }

    @Test
    void aHeldSourceOnAQueryRouteLintsClean(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).noneMatch(LintFinding::isError);
        // The key is the model's now, not an unknown one silently ignored.
        assertThat(findings).noneMatch(f -> "TQL-YAML-1043".equals(f.code()));
    }

    @Test
    void aTransactionalRouteRefusesTheHold(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "command-json", """
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                sources:
                  after:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: after.rows
                """);
        assertThat(held(new AppLinter().lint(dir))).singleElement().satisfies(f -> {
            assertThat(f.isError()).isTrue();
            assertThat(f.message()).contains("app 't': route 'orders.route'",
                    "sources.after.cache:", "holds nothing on a 'command-json' route",
                    "stale read of it");
        });
    }

    @Test
    void aStepRefusesTheHold(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "command-json", """
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      ok: steps.main.affectedRows
                """);
        assertThat(held(new AppLinter().lint(dir))).singleElement().satisfies(f -> assertThat(
                f.message()).contains("steps.main.cache:", "holds nothing on a step"));
    }

    @Test
    void aStreamingRouteRefusesTheHold(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-export", """
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                """);
        assertThat(held(new AppLinter().lint(dir))).singleElement().satisfies(f -> assertThat(
                f.message()).contains("'query-export' route", "streams to the codec"));
    }

    @Test
    void anArmWithNoStatementRefusesTheHold(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    service:
                      name: some.provider
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        assertThat(held(new AppLinter().lint(dir))).singleElement().satisfies(f -> assertThat(
                f.message()).contains("needs a statement to key", "service: arm"));
    }

    @Test
    void aBadMaxAgeAndAMissingTablesAreEachTheirOwnArm(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: whenever
                  second:
                    sql:
                      file: orders.sql
                    cache:
                      tables: [orders]
                  third:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 0s
                      tables: [orders, ""]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        List<LintFinding> findings = held(new AppLinter().lint(dir));
        assertThat(findings).extracting(LintFinding::message)
                .anySatisfy(m -> assertThat(m).contains("sources.main.cache:",
                        "'whenever' is not a duration"))
                .anySatisfy(m -> assertThat(m).contains("sources.main.cache:",
                        "declares no tables:"))
                .anySatisfy(m -> assertThat(m).contains("sources.second.cache:",
                        "declares no maxAge:"))
                .anySatisfy(m -> assertThat(m).contains("sources.third.cache:",
                        "'0s' is not positive"))
                .anySatisfy(m -> assertThat(m).contains("sources.third.cache:",
                        "carries a blank name"));
        assertThat(findings).hasSize(5);
    }

    @Test
    void aWriteNamingAHeldTableDropsSomethingAndAnUnheldOneIsAWarning(@TempDir Path dir)
            throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        Path write = Files.createDirectories(dir.resolve("web/orders/adjust"));
        Files.writeString(write.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(write.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.adjust
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                invalidates: [orders, customers]
                response:
                  json:
                    body:
                      ok: steps.main.affectedRows
                """);
        List<LintFinding> findings = new AppLinter().lint(dir).stream()
                .filter(f -> "TQL-FIELD-4620".equals(f.code())).toList();
        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.isError()).isFalse();
            assertThat(f.message()).contains("'customers'",
                    "no catalog and no held source reads",
                    "catalogs and held sources read orders");
        });
    }

    /** docs/caching.md decision 9: a reference's hold, judged per arm. */
    @Test
    void aReferencesHoldIsLegalOnAFetchAndRefusedOnASiblingOrWithTablesOnHttp(
            @TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    sql:
                      file: orders.sql
                    enrich:
                      partner:
                        on: { id: code }
                        sql:
                          file: partners.sql
                        merge: [name]
                        cache:
                          maxAge: 30s
                          tables: [partners]
                      remote:
                        on: { id: code }
                        http:
                          url: http://partners.test/partners/{key.code}
                        merge: [name]
                        cache:
                          maxAge: 30s
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(findings).noneMatch(LintFinding::isError);
        assertThat(findings).noneMatch(f -> "TQL-YAML-1043".equals(f.code()));

        writeRoute(dir.resolve("refused"), "orders", "query-json", """
                sources:
                  other:
                    sql:
                      file: orders.sql
                  main:
                    sql:
                      file: orders.sql
                    enrich:
                      nested:
                        on: { id: id }
                        source: other
                        as: lines
                        cache:
                          maxAge: 30s
                      remote:
                        on: { id: code }
                        http:
                          url: http://partners.test/partners/{key.code}
                        merge: [name]
                        cache:
                          maxAge: 30s
                          tables: [partners]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        List<LintFinding> refused = held(new AppLinter().lint(dir.resolve("refused")));
        assertThat(refused).extracting(LintFinding::message)
                .anySatisfy(m -> assertThat(m).contains("sources.main.enrich.nested.cache:",
                        "holds nothing over a sibling source (source: other)"))
                .anySatisfy(m -> assertThat(m).contains("sources.main.enrich.remote.cache:",
                        "an http: reference takes maxAge: alone"));
        assertThat(refused).hasSize(2);
    }

    /**
     * docs/caching.md: every writer with a commit may invalidate — a queue consumer and a file
     * import beside the command, and a job after its run — each judged by the one table arm;
     * a read route still cannot, because nothing commits after it.
     */
    @Test
    void everyWriterWithACommitMayInvalidateAndAReadMayNot(@TempDir Path dir) throws Exception {
        writeRoute(dir, "orders", "query-json", """
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        Path consume = Files.createDirectories(dir.resolve("consume/orders"));
        Files.writeString(consume.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(consume.resolve("project.yml"), """
                version: tesseraql/v1
                id: orders.project
                kind: route
                recipe: queue-consume
                consume:
                  channel: events
                  topic: orders.created
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                invalidates: [orders]
                """);
        Path upload = Files.createDirectories(dir.resolve("web/orders/upload"));
        Files.writeString(upload.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(upload.resolve("post.yml"), """
                version: tesseraql/v1
                id: orders.upload
                kind: route
                recipe: file-import
                security:
                  auth: public
                import:
                  format: csv
                  columns:
                    - n
                steps:
                  - id: row
                    sql:
                      file: write.sql
                invalidates: [orders]
                """);
        Path job = Files.createDirectories(dir.resolve("batch/nightly"));
        Files.writeString(job.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(job.resolve("job.yml"), """
                version: tesseraql/v1
                id: orders.nightly
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                invalidates: [orders, customers]
                """);
        Path read = Files.createDirectories(dir.resolve("web/orders/stale"));
        Files.writeString(read.resolve("orders.sql"), "select 1 as id\n");
        Files.writeString(read.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.stale
                kind: route
                recipe: query-json
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: orders.sql
                invalidates: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        List<LintFinding> findings = new AppLinter().lint(dir).stream()
                .filter(f -> "TQL-FIELD-4620".equals(f.code())).toList();
        // The consumer, the import and the job's held table: nothing to say. Two findings:
        // the job's unheld table (a warning, as a command's is) and the read (an error).
        assertThat(findings).hasSize(2);
        assertThat(findings).filteredOn(LintFinding::isError).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo("web/orders/stale/get.yml");
            assertThat(f.message()).contains("a recipe that commits", "'query-json'");
        });
        assertThat(findings).filteredOn(f -> !f.isError()).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo("batch/nightly/job.yml");
            assertThat(f.message()).contains("'customers'", "no catalog and no held source reads");
        });
    }

    @Test
    void aReadToolMayHoldAndAWritingToolMayNot(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), CONFIG);
        Path tools = Files.createDirectories(dir.resolve("mcp/tools/lookup"));
        Files.writeString(tools.resolve("orders.sql"), "select 1 as id\n");
        Files.writeString(tools.resolve("tool.yml"), """
                version: tesseraql/v1
                id: lookup.orders
                kind: tool
                recipe: query-json
                description: the orders
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        assertThat(held(new AppLinter().lint(dir))).isEmpty();

        Path writer = Files.createDirectories(dir.resolve("mcp/tools/touch"));
        Files.writeString(writer.resolve("write.sql"), "update orders set n = 1\n");
        Files.writeString(writer.resolve("orders.sql"), "select 1 as id\n");
        Files.writeString(writer.resolve("tool.yml"), """
                version: tesseraql/v1
                id: touch.orders
                kind: tool
                recipe: command-json
                description: touches the orders
                security:
                  auth: public
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                sources:
                  after:
                    sql:
                      file: orders.sql
                    cache:
                      maxAge: 30s
                      tables: [orders]
                response:
                  json:
                    body:
                      rows: after.rows
                """);
        assertThat(held(new AppLinter().lint(dir))).singleElement().satisfies(f -> assertThat(
                f.message()).contains("MCP tool 'touch.orders'", "tool that writes"));
    }
}
