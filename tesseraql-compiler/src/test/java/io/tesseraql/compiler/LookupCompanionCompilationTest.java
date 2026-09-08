package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;

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
 * A {@code lookup:} field's companion routes are compiled from the view registry the manifest
 * already holds, not from a second read of the view file.
 *
 * <p>The companion scan used to call {@code ViewSpec.parse(view.source())} for every view, on
 * every lookup-bearing route, and swallow any {@code RuntimeException} — so a view file that
 * moved between the manifest load and the compile produced no companion pipelines and no
 * complaint. Nothing failed; the search dialog simply was not there. That silence is what these
 * cases pin: they assert the companions exist, against a tree where the second read cannot
 * succeed.
 */
class LookupCompanionCompilationTest {

    private static final List<String> COMPANIONS = List.of(
            "orders.create._lookup.customer_id",
            "orders.create._lookup.customer_id.dialog",
            "orders.create._lookup.customer_id.results");

    @Test
    void theCompanionsCompileFromTheRegistry(@TempDir Path dir) throws Exception {
        writeApp(dir);

        assertThat(compile(new ManifestLoader().load(dir)))
                .as("the lookup companions compile from a manifest whose views are all on disk")
                .containsKeys(COMPANIONS.toArray(new String[0]));
    }

    /**
     * The view file is deleted <em>after</em> the manifest has been loaded, so the registry holds
     * a parsed spec that no longer has a file behind it. A re-read throws
     * {@code UncheckedIOException}, which the deleted {@code catch} treated as "unparseable, skip
     * it" — leaving the route compiled with no companions at all.
     */
    @Test
    void theCompanionsSurviveTheViewFileGoingAwayAfterTheLoad(@TempDir Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        Files.delete(dir.resolve("web/orders/new/new.view.yml"));

        assertThat(compile(manifest))
                .as("the lookup companions survive a view file deleted after the manifest load")
                .containsKeys(COMPANIONS.toArray(new String[0]));
    }

    /**
     * The same defect without needing a delete: the file is rewritten with a different
     * {@code action:}. A re-read matches nothing and drops the companions; the registry's
     * snapshot still names the path the manifest was loaded with.
     */
    @Test
    void theCompanionsFollowTheLoadedSpecNotTheFileOnDisk(@TempDir Path dir) throws Exception {
        writeApp(dir);
        AppManifest manifest = new ManifestLoader().load(dir);
        Files.writeString(dir.resolve("web/orders/new/new.view.yml"), """
                version: tesseraql/v1
                id: orders.new.form
                kind: view
                recipe: form
                title: New order
                action: /orders/somewhere-else
                """);

        assertThat(compile(manifest))
                .as("the lookup companions read the spec the manifest loaded, not the file now"
                        + " on disk")
                .containsKeys(COMPANIONS.toArray(new String[0]));
    }

    private static Map<String, List<String>> compile(AppManifest manifest) throws Exception {
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("lookup-companion-test")
                    .compile(context, manifest, false, null);
            Map<String, List<String>> byId = CompiledPipelines.stepsById(context);
            // Anti-vacuity: the two declared routes must be there, or a compile that produced
            // nothing at all would satisfy nothing and read as a pass on an empty map.
            assertThat(byId).containsKeys("orders.create", "customers.search");
            return byId;
        }
    }

    /**
     * The smallest app that compiles a {@code lookup:}. The GET route is mandatory —
     * {@code source:} must match one, and its main SQL is parsed off disk — and the form view is
     * deliberately an orphan: no route names it through {@code response.html.view}, because that
     * path parses the file itself and would blow the compile up instead of producing the clean
     * assertion these cases are written for.
     */
    private static void writeApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: lookup-companion-test
                """);

        Files.createDirectories(dir.resolve("web/api/customers/search"));
        Files.writeString(dir.resolve("web/api/customers/search/get.yml"), """
                version: tesseraql/v1
                id: customers.search
                kind: route
                recipe: query-json
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: search.sql
                      mode: query
                response:
                  json:
                    body:
                      rows: main.rows
                """);
        Files.writeString(dir.resolve("web/api/customers/search/search.sql"),
                "select customer_code, name from customers\n");

        Files.createDirectories(dir.resolve("web/orders/new"));
        Files.writeString(dir.resolve("web/orders/new/post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                input:
                  customer_id:
                    type: string
                    lookup:
                      source: /api/customers/search
                      code: customer_code
                      label: name
                steps:
                  - id: main
                    sql:
                      file: create.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: true
                """);
        Files.writeString(dir.resolve("web/orders/new/create.sql"),
                "insert into orders (customer_id) values (/* customer_id */'c1')\n");
        Files.writeString(dir.resolve("web/orders/new/new.view.yml"), """
                version: tesseraql/v1
                id: orders.new.form
                kind: view
                recipe: form
                title: New order
                action: /orders/new
                """);
    }
}
