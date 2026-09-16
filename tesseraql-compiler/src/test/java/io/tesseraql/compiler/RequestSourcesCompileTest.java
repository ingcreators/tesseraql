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
 * The boot twin of the request-source lint (docs/audit-low-leads.md slice 9): a
 * {@code header.<name>} source anywhere but a {@code service:} binding's {@code params:} is
 * refused where the route compiles, with the code and the sentence the linter reports, naming
 * the route — a statement would have bound null on every request, which is the silence the
 * binder's own header fallback used to fill. The declared shape compiles.
 */
class RequestSourcesCompileTest {

    @Test
    void aHeaderSourceOnAStatementIsRefusedNamingTheRoute(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> compile(dir, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    sql:
                      file: list.sql
                      params:
                        q: header.Cookie
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1069")
                .hasMessageContaining("route 'items.route' sources.main params: q: 'header.Cookie'")
                .hasMessageContaining("only a service: binding's params: may");
        assertThatThrownBy(() -> compile(dir.resolve("step"), "command-json", """
                response:
                  json:
                    body:
                      ok: true
                steps:
                  - id: write
                    sql:
                      file: write.sql
                      mode: update
                      params:
                        n: header.X-Count
                """))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1069")
                .hasMessageContaining("route 'items.route' steps.write params: n");
    }

    @Test
    void aHeaderSourceOnAServiceBindingCompiles(@TempDir Path dir) throws Exception {
        assertThat(compile(dir, "query-json", """
                response:
                  json:
                    body:
                      rows: main.rows
                sources:
                  main:
                    service:
                      name: ops.shell.home
                      params:
                        cookie: header.Cookie
                """).get("items.route")).contains("NamedQueryBinder").contains("ServiceStep");
    }

    // ---- harness ----

    private static Map<String, List<String>> compile(Path dir, String recipe, String body)
            throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: sources-test
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
                  auth: public
                %s
                """.formatted(recipe, body));
        Files.writeString(route.resolve("list.sql"), "select id from items where id = /* q */1\n");
        Files.writeString(route.resolve("write.sql"), "insert into items (id) values (/* n */1)\n");
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("sources-test").compile(context, manifest, false, null);
            return CompiledPipelines.stepsById(context);
        }
    }
}
