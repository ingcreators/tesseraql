package io.tesseraql.compiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.pipeline.auth.AuthStep;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code response.session.rotate} either rotates or refuses — never a silent no-op.
 *
 * <p>Only the JSON and command builders applied it; on a page recipe the declaration compiled,
 * booted, and served while rotating nothing — a session-fixation control that silently does not
 * exist, on exactly the post-sign-in confirmation page it is written for
 * (docs/session-rotation.md, docs/silent-tolerance.md). Pages now honour it; a recipe with no
 * browser session to rotate refuses the declaration at build time with the recipe named.
 */
class SessionRotationRecipesTest {

    @Test
    void aPageDeclaringRotationCompilesTheRotateStep(@TempDir Path dir) throws Exception {
        writePage(dir, "recipe: page", """
                response:
                  html:
                    template: page.html
                  session:
                    rotate: true
                """);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            new RouteCompiler().appName("rotation-test").compile(context, manifest, false, null);

            assertThat(CompiledPipelines.steps(context, AuthStep.class))
                    .as("the declared rotation is a compiled step, not a silent no-op")
                    .anyMatch(step -> "rotate".equals(step.operation()));
        }
    }

    @Test
    void aRecipeWithNoBrowserSessionRefusesTheDeclaration(@TempDir Path dir) throws Exception {
        writePage(dir, "recipe: query-export", """
                export:
                  format: csv
                response:
                  session:
                    rotate: true
                """);
        AppManifest manifest = new ManifestLoader().load(dir);
        try (RuntimeContext context = new RuntimeContext()) {
            assertThatThrownBy(() -> new RouteCompiler().appName("rotation-test")
                    .compile(context, manifest, false, null))
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("response.session.rotate")
                    .hasMessageContaining("query-export");
        }
    }

    private static void writePage(Path dir, String recipe, String tail) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: rotation-test
                """);
        Path route = dir.resolve("web/confirm");
        Files.createDirectories(route);
        Files.writeString(route.resolve("get.yml"), """
                version: tesseraql/v1
                id: confirm.page
                kind: route
                %s
                security:
                  auth: public
                sources:
                  main:
                    sql:
                      file: list.sql
                      mode: query
                %s""".formatted(recipe, tail));
        Files.writeString(route.resolve("list.sql"), "select id, name from items\n");
        Files.writeString(route.resolve("page.html"), "<p>x</p>\n");
    }
}
