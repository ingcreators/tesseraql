package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
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
 * The boot twin of the declaration predicates docs/audit-low-leads.md slice 13 lifted out of
 * the linter or out of a builder's own code: each is refused where the route compiles, with
 * the code and the sentence the linter reports, naming the app and the route. A lint↔boot
 * differential is green on a shared defect, so every row here asserts the refusal itself —
 * the row is red when the predicate's arm is missing, whichever altitude asks.
 */
class JudgedOnceCompileTest {

    /** XD-07b: a default the input refuses is refused before any request binds it. */
    @Test
    void aDefaultTheInputRefusesIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-json", "public", """
                input:
                  n:
                    type: number
                    default: abc
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1072")
                .hasMessageContaining("app 'judged-once'")
                .hasMessageContaining("route 'items.route' input.n.default")
                .hasMessageContaining("'abc'")
                .hasMessageContaining("not a number");
        assertThat(compile(dir.resolve("typed"), "query-json", "public", """
                input:
                  n:
                    type: integer
                    default: "5"
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                """)).containsKey("items.route");
    }

    /** XD-07b, the filed instance: a sourced input's default that is not a zone. */
    @Test
    void aSourcedInputsDefaultThatIsNotAZoneIsRefusedAtCompile(@TempDir Path dir)
            throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "public", """
                input:
                  tz:
                    type: string
                    default: Asia/Tokio
                export:
                  format: csv
                  timezone: query.tz
                sources:
                  main:
                    sql:
                      file: list.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1063")
                .hasMessageContaining("route 'items.route' export.timezone")
                .hasMessageContaining("input tz's default: 'Asia/Tokio' is not a time-zone id");
    }

    /** EH-06: a file response's charset the body is not written in. */
    @Test
    void aCharsetTheBodyIsNotWrittenInIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "page", "public", """
                response:
                  file:
                    template: receipt.txt
                    contentType: "text/plain; charset=Shift_JIS"
                sources:
                  main:
                    sql:
                      file: list.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1073")
                .hasMessageContaining("route 'items.route' response.file.contentType")
                .hasMessageContaining("the body is written as UTF-8");
    }

    /**
     * docs/route-filename-placeholders.md decision 5: a download name's placeholder the request
     * cannot resolve, on the export block and on the file response; the resolvable spellings
     * compile (the route lives at {@code /items}, so {@code path.*} has no parameter to name).
     */
    @Test
    void anUnresolvableFilenamePlaceholderIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "public", """
                input:
                  month:
                    type: string
                export:
                  format: csv
                  filename: "items-{params.month}-{now}.csv"
                sources:
                  main:
                    sql:
                      file: list.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1076")
                .hasMessageContaining("route 'items.route' export.filename")
                .hasMessageContaining("{now}")
                .hasMessageContaining("names no request root");
        assertThatThrownBy(() -> compile(dir.resolve("file"), "page", "public", """
                response:
                  file:
                    template: receipt.txt
                    contentType: text/plain
                    filename: "items-{path.id}.txt"
                sources:
                  main:
                    sql:
                      file: list.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1076")
                .hasMessageContaining("route 'items.route' response.file.filename")
                .hasMessageContaining("{path.id}")
                .hasMessageContaining("path parameter the route's URL does not declare");
        assertThat(compile(dir.resolve("clean"), "page", "public", """
                input:
                  month:
                    type: string
                response:
                  file:
                    template: receipt.txt
                    contentType: text/plain
                    filename: "items-{params.month}-{main.rowCount}.txt"
                sources:
                  main:
                    sql:
                      file: list.sql
                """)).containsKey("items.route");
    }

    /** EH-06: whitespace at either end of a redirect location. */
    @Test
    void whitespaceOnARedirectLocationIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        for (String location : List.of("\"/items \"", "\" /items\"")) {
            assertThatThrownBy(() -> compile(dir.resolve(String.valueOf(location.hashCode())),
                    "command-json", "public", """
                            response:
                              redirect:
                                location: %s
                            steps:
                              - id: write
                                sql:
                                  file: write.sql
                                  mode: update
                            """.formatted(location)))
                    .as(location)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1074")
                    .hasMessageContaining("route 'items.route' response.redirect.location")
                    .hasMessageContaining("whitespace at its");
        }
    }

    /** The result: sweep: an export recipe applies no declaration, so one there is refused. */
    @Test
    void aDeclarationOnAnExportRecipeIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-export", "public", """
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: list.sql
                    result:
                      note: { type: json }
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1064")
                .hasMessageContaining("route 'items.route' sources.main.result:")
                .hasMessageContaining("query-export hands every source to the export writer");
    }

    /** XD-07a: a principal source that is not a claim, on an authenticated route. */
    @Test
    void aPrincipalSourceThatIsNotAClaimIsRefusedAtCompile(@TempDir Path dir) throws Exception {
        for (String source : List.of("principal.subject", "principal.zoneinfo",
                "principal.claims.zoneinfo")) {
            assertThatThrownBy(() -> compile(dir.resolve(source.replace('.', '_')),
                    "query-export", "bearer", """
                            export:
                              format: csv
                              timezone: %s
                            sources:
                              main:
                                sql:
                                  file: list.sql
                            """.formatted(source)))
                    .as(source)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-YAML-1063")
                    .hasMessageContaining("route 'items.route' export.timezone")
                    .hasMessageContaining("'" + source + "' is not a claim");
        }
        assertThat(compile(dir.resolve("claim"), "query-export", "bearer", """
                export:
                  format: csv
                  timezone: principal.claim.zoneinfo
                sources:
                  main:
                    sql:
                      file: list.sql
                """)).containsKey("items.route");
    }

    // ---- harness ----

    /**
     * docs/list-export.md decision 6: a list view's export route that would refuse the list's
     * question fails the build with the lint's code and sentence, before a page renders a
     * control that answers 400 on its first click.
     */
    @Test
    void anExportRouteRefusingTheListsQuestionIsRefusedAtCompile(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("web/items/export"));
        Files.writeString(dir.resolve("web/items/items.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                search: q
                exports: [/items/export]
                """);
        Files.writeString(dir.resolve("web/items/export/get.yml"), """
                version: tesseraql/v1
                id: items.export
                kind: route
                recipe: query-export
                security:
                  auth: public
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: ../list.sql
                """);
        assertThatThrownBy(() -> compile(dir, "query-html", "public", """
                input:
                  q: { type: string, required: false }
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: query.q
                response:
                  html:
                    view: items
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3331")
                .hasMessageContaining("view items: export /items/export")
                .hasMessageContaining("does not declare the list's input 'q'");
    }

    private static Map<String, List<String>> compile(Path dir, String recipe, String auth,
            String body) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: judged-once
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/none
                """);
        Path route = Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: %s
                %s
                """.formatted(recipe, auth, body));
        Files.writeString(route.resolve("list.sql"), "select id, note from items\n");
        Files.writeString(route.resolve("write.sql"), "update items set n = 1 where id = 1\n");
        Files.writeString(route.resolve("receipt.txt"), "Total: [(${main.rows})]\n");
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("judged-once").compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
