package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.sql.SqlStep;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The boot twin of the held-source predicate (docs/caching.md decision 6): a {@code cache:}
 * where nothing can be held is refused where the route compiles, with the code and the
 * sentence the linter reports; a legal one compiles to a {@code SqlStep} carrying its spec,
 * and a command's step never does. A lint↔boot differential is green on a shared defect, so
 * every row here asserts the refusal itself.
 */
class HeldSourcesCompileTest {

    @Test
    void aHeldSourceCompilesToAStepCarryingItsSpec(@TempDir Path dir) throws Exception {
        try (RuntimeContext context = compile(dir, "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                    cache:
                      maxAge: 45s
                      tables: [items, categories]
                  plain:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      rows: main.rows
                """)) {
            List<SqlStep> steps = CompiledPipelines.steps(context, "items.route", SqlStep.class);
            assertThat(steps).hasSize(2);
            assertThat(steps.get(0).hold()).satisfies(spec -> {
                assertThat(spec.owner()).isEqualTo("items.route");
                assertThat(spec.source()).isEqualTo("main");
                assertThat(spec.datasource()).isEqualTo("main");
                assertThat(spec.maxAgeMillis()).isEqualTo(45_000L);
                assertThat(spec.tables()).containsExactly("items", "categories");
            });
            assertThat(steps.get(1).hold()).as("a source with no cache: holds nothing").isNull();
        }
    }

    /** docs/caching.md decision 9: a reference's hold rides its processor; a sibling's is refused. */
    @Test
    void aHeldReferenceCompilesToItsProcessorAndASiblingsIsRefused(@TempDir Path dir)
            throws Exception {
        try (RuntimeContext context = compile(dir, "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                    enrich:
                      partner:
                        on: { id: code }
                        sql:
                          file: partners.sql
                        merge: [name]
                        cache:
                          maxAge: 2m
                          tables: [partners]
                response:
                  json:
                    body:
                      rows: main.rows
                """)) {
            List<io.tesseraql.compiler.binding.EnrichProcessor> processors = CompiledPipelines
                    .steps(context, "items.route",
                            io.tesseraql.compiler.binding.EnrichProcessor.class);
            assertThat(processors).hasSize(1);
            assertThat(processors.get(0).hold()).satisfies(spec -> {
                assertThat(spec.owner()).isEqualTo("items.route");
                assertThat(spec.source()).isEqualTo("main.enrich.partner");
                assertThat(spec.maxAgeMillis()).isEqualTo(120_000L);
                assertThat(spec.tables()).containsExactly("partners");
            });
        }
        assertThatThrownBy(() -> compile(dir.resolve("sibling"), "query-json", """
                sources:
                  other:
                    sql:
                      file: list.sql
                  main:
                    sql:
                      file: list.sql
                    enrich:
                      nested:
                        on: { id: id }
                        source: other
                        as: lines
                        cache:
                          maxAge: 30s
                response:
                  json:
                    body:
                      rows: main.rows
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("sources.main.enrich.nested.cache")
                .hasMessageContaining("sibling source");
    }

    @Test
    void aHoldOnATransactionalRouteIsRefusedAtCompile(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "command-json", """
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                sources:
                  after:
                    sql:
                      file: list.sql
                    cache:
                      maxAge: 30s
                      tables: [items]
                response:
                  json:
                    body:
                      rows: after.rows
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("app 'held'")
                .hasMessageContaining("route 'items.route' sources.after.cache")
                .hasMessageContaining("holds nothing on a 'command-json' route");
    }

    @Test
    void aHoldOnAStepIsRefusedAtCompile(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "command-json", """
                steps:
                  - id: main
                    sql:
                      file: write.sql
                      mode: update
                    cache:
                      maxAge: 30s
                      tables: [items]
                response:
                  json:
                    body:
                      ok: steps.main.affectedRows
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("steps.main.cache")
                .hasMessageContaining("holds nothing on a step");
    }

    @Test
    void aHoldWithNoTablesOrABadAgeIsRefusedAtCompile(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                    cache:
                      maxAge: 30s
                response:
                  json:
                    body:
                      rows: main.rows
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("declares no tables:");
        assertThatThrownBy(() -> compile(dir.resolve("age"), "query-json", """
                sources:
                  main:
                    sql:
                      file: list.sql
                    cache:
                      maxAge: soon
                      tables: [items]
                response:
                  json:
                    body:
                      rows: main.rows
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("'soon' is not a duration");
    }

    @Test
    void aHoldOnAStreamingRouteIsRefusedAtCompile(@TempDir Path dir) {
        assertThatThrownBy(() -> compile(dir, "query-export", """
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: list.sql
                    cache:
                      maxAge: 30s
                      tables: [items]
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1077")
                .hasMessageContaining("'query-export' route");
    }

    private static RuntimeContext compile(Path dir, String recipe, String body)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: held
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/none
                """);
        Path route = Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(route.resolve(recipe.startsWith("command") ? "post.yml" : "get.yml"),
                """
                        version: tesseraql/v1
                        id: items.route
                        kind: route
                        recipe: %s
                        security:
                          auth: public
                        %s
                        """.formatted(recipe, body));
        Files.writeString(route.resolve("list.sql"), "select id, note from items\n");
        Files.writeString(route.resolve("write.sql"), "update items set n = 1 where id = 1\n");
        Files.writeString(route.resolve("partners.sql"),
                "select code, name from partners where code in /* keys */(1)\n");
        AppManifest manifest = new ManifestLoader().load(dir);
        RuntimeContext context = new RuntimeContext();
        try {
            new RouteCompiler().appName("held").compile(context, manifest, false, null);
        } catch (RuntimeException refused) {
            context.close();
            throw refused;
        }
        return context;
    }
}
