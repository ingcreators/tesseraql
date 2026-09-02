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
 * What {@code import.review: required} costs at compile time (docs/csv-import.md decision 1 and
 * decision 5): the confirm leg is mounted, and the two declarations that cannot mean what they
 * say are refused where they are written rather than accepted and quietly ignored.
 */
class ImportReviewRecipeTest {

    @Test
    void aReviewedImportMountsTheConfirmLeg(@TempDir Path dir) throws Exception {
        Map<String, List<String>> pipelines = compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  columns: [name, qty]
                  review: required
                """);

        assertThat(pipelines).containsKey("items.import.commit");
        assertThat(pipelines.get("items.import.commit")).contains("ImportCommitProcessor");
        // The status leg is no longer security-only: it carries the same governance the parent
        // route does, so a transfer's state is traced and its refusals localize. (Tenancy rides
        // the same call and adds its step only where the app declares tenants.)
        assertThat(pipelines.get("items.import.status"))
                .contains("RouteTelemetry", "LocaleResolution");
    }

    @Test
    void aOneShotImportMountsNoConfirmLeg(@TempDir Path dir) throws Exception {
        Map<String, List<String>> pipelines = compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  columns: [name, qty]
                """);

        assertThat(pipelines).doesNotContainKey("items.import.commit");
    }

    @Test
    void aReviewValueOtherThanRequiredIsRefused(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  review: optional
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3118")
                .hasMessageContaining("the only accepted value is 'required'");
    }

    @Test
    void aReviewedImportWithoutAuthenticationIsRefused(@TempDir Path dir) throws Exception {
        // Otherwise every batch has the same empty owner, and anyone could confirm anyone's
        // upload while the code still looks like it is scoping.
        assertThatThrownBy(() -> compile(dir, """
                import:
                  format: csv
                  review: required
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3118")
                .hasMessageContaining("security.auth");
    }

    @Test
    void aOneShotImportDeclaringAPageIsRefused(@TempDir Path dir) throws Exception {
        // `response.html:` on a file recipe used to lint clean, compile and be dropped. It is
        // honoured now, and refused where it cannot mean anything: a one-shot import has no
        // report to render (docs/csv-import.md decision 7).
        assertThatThrownBy(() -> compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  columns: [name, qty]
                response:
                  html:
                    view: items-import
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-ROUTE-3118")
                .hasMessageContaining("response.html:");
    }

    @Test
    void aReviewedImportDeclaringAPageCompilesTheView(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/api/items/import"));
        Files.writeString(dir.resolve("web/api/items/import/items-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                title: Import items
                action: /api/items/import
                """);
        Map<String, List<String>> pipelines = compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  columns: [name, qty]
                  review: required
                response:
                  html:
                    view: items-import
                """);

        // One processor still, because the page is the same answer negotiated: the report is
        // what the parse found either way (docs/csv-import.md decision 1).
        assertThat(pipelines.get("items.import")).contains("FileImportProcessor");
    }

    @Test
    void anImportViewMustNameAFileImportRoute(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/api/items/import"));
        Files.writeString(dir.resolve("web/api/items/import/items-import.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: import
                action: /api/items/nowhere
                """);
        assertThatThrownBy(() -> compile(dir, """
                security:
                  auth: bearer
                  policy: items.write
                import:
                  format: csv
                  review: required
                response:
                  html:
                    view: items-import
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("matches no POST route");
    }

    private static Map<String, List<String>> compile(Path dir, String body) throws Exception {
        writeApp(dir, body);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("import-test")
                    .compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }

    private static void writeApp(Path dir, String body) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: import-test
                """);
        Path route = Files.createDirectories(dir.resolve("web/api/items/import"));
        Files.writeString(route.resolve("post.yml"), """
                version: tesseraql/v1
                id: items.import
                kind: route
                recipe: file-import

                """ + body + """
                steps:
                  - id: row
                    sql:
                      file: upsert.sql
                """);
        Files.writeString(route.resolve("upsert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
    }
}
