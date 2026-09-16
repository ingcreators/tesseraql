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
 * The boot twin of the route-files lint (docs/audit-low-leads.md slice 14): a reference that
 * resolves outside the application home is refused where the route compiles, with the code
 * and the sentence the linter reports, naming the route and the key — before the builder
 * that used to hand the path, as resolved, to the SQL source or the codec. Every escaping
 * fixture here compiled on the measured tree: the statement outside the home ran, the
 * workbook outside it was delivered. The app home is a subdirectory of the temp root, so
 * {@code ../../../} leaves it while {@code ../shared/} stays inside.
 */
class RouteFilesCompileTest {

    @Test
    void aStatementOutsideTheHomeIsRefusedNamingTheRouteAndTheKey(@TempDir Path root)
            throws Exception {
        assertThatThrownBy(() -> compile(root, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: ../../../outside.sql
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("route 'items.route' sources.main.file:")
                .hasMessageContaining("'../../../outside.sql' resolves outside the application"
                        + " home");
        assertThatThrownBy(() -> compile(root.resolve("step"), "command-json", """
                response:
                  json:
                    body:
                      ok: true
                steps:
                  - id: write
                    sql:
                      file: ../../../outside.sql
                      mode: update
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("route 'items.route' steps.write.file:");
    }

    @Test
    void anExportTemplateOutsideTheHomeIsRefusedForEveryFormat(@TempDir Path root)
            throws Exception {
        // The export block's own arm, with the fence the codec used to be handed the path
        // without — csv ignores a template, so the workbook formats are the ones that read it.
        assertThatThrownBy(() -> compile(root, "query-export", """
                sources:
                  main:
                    sql:
                      file: list.sql
                export:
                  format: excel
                  template: ../../../outside.xlsx
                  startCell: A2
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("route 'items.route' export.template:")
                .hasMessageContaining("'../../../outside.xlsx' resolves outside the application"
                        + " home");
    }

    @Test
    void aPageTemplateOutsideTheHomeIsRefusedNamingTheRoute(@TempDir Path root)
            throws Exception {
        // Refused before the renderer is built, so the sentence carries the route and the
        // key — the renderer's own refusal named neither.
        assertThatThrownBy(() -> compile(root, "page", """
                response:
                  html:
                    template: ../../../outside.html
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1075")
                .hasMessageContaining("route 'items.route' response.html.template:");
        assertThatThrownBy(() -> compile(root.resolve("gone"), "page", """
                response:
                  html:
                    template: nowhere.html
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-TPL-2001")
                .hasMessageContaining("route 'items.route' response.html.template:")
                .hasMessageContaining("'nowhere.html' resolves to no file beside the document"
                        + " or under templates/");
    }

    @Test
    void anExportsMissingMainStatementIsRefusedAtCompile(@TempDir Path root) throws Exception {
        // The export builders resolved main themselves and never asked whether it was there:
        // a query-export over a missing statement compiled, and failed its first download.
        assertThatThrownBy(() -> compile(root, "query-export", """
                sources:
                  main:
                    sql:
                      file: nowhere.sql
                export:
                  format: csv
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2103")
                .hasMessageContaining("route 'items.route' sources.main.file:")
                .hasMessageContaining("referenced SQL file is missing: nowhere.sql");
    }

    @Test
    void aReferenceInsideTheHomeCompilesWhereverItSits(@TempDir Path root) throws Exception {
        Path home = home(root);
        Files.createDirectories(home.resolve("shared"));
        Files.writeString(home.resolve("shared/order.sql"), "select id from orders\n");
        Files.createDirectories(home.resolve("templates"));
        Files.writeString(home.resolve("templates/layout.html"), "<p th:text=\"${v}\"></p>\n");
        Path route = Files.createDirectories(home.resolve("web/orders/detail/q"));
        Files.writeString(route.resolve("rows.sql"), "select 1\n");
        Files.writeString(home.resolve("web/orders/detail/get.yml"), """
                version: tesseraql/v1
                id: orders.detail
                kind: route
                recipe: query-html
                security:
                  auth: public
                response:
                  html:
                    template: layout.html
                sources:
                  main:
                    sql:
                      file: q/rows.sql
                  order:
                    sql:
                      file: ../../../shared/order.sql
                """);
        assertThat(compileApp(home).get("orders.detail")).isNotEmpty();
    }

    // ---- harness ----

    private static Path home(Path root) throws Exception {
        Path home = Files.createDirectories(root.resolve("app"));
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: files-test
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/none
                """);
        Files.writeString(root.resolve("outside.sql"), "select id from items\n");
        Files.writeString(root.resolve("outside.html"), "<p>outside</p>\n");
        Files.writeString(root.resolve("outside.xlsx"), "PK\3\4");
        return home;
    }

    private static Map<String, List<String>> compile(Path root, String recipe, String body)
            throws Exception {
        Path home = home(root);
        Path route = Files.createDirectories(home.resolve("web/items"));
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: items.route
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                """.formatted(recipe, body));
        Files.writeString(route.resolve("list.sql"), "select id from items\n");
        return compileApp(home);
    }

    private static Map<String, List<String>> compileApp(Path home) throws Exception {
        AppManifest manifest = new ManifestLoader().load(home);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("files-test")
                    .codecs(io.tesseraql.core.files.FileCodecs.of(
                            new io.tesseraql.operations.files.CsvFileCodec()))
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
