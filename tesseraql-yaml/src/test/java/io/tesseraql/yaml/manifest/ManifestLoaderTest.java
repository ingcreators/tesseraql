package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class ManifestLoaderTest {

    private static Path exampleApp() {
        // Tests run from the module directory; the example app lives at the repo root.
        Path candidate = Paths.get("..", "examples", "user-admin-app");
        return candidate.toAbsolutePath().normalize();
    }

    @Test
    void loadsRoutesWithUrlMapping() {
        AppManifest manifest = new ManifestLoader().load(exampleApp());

        assertThat(manifest.routes())
                .anySatisfy(route -> {
                    assertThat(route.httpMethod()).isEqualTo("GET");
                    assertThat(route.urlPath()).isEqualTo("/api/users");
                    assertThat(route.definition().recipe()).isEqualTo("query-json");
                })
                .anySatisfy(route -> {
                    // htmx prefix is stripped from the served path (design ch. 4.2).
                    assertThat(route.urlPath()).isEqualTo("/users/fragments/table");
                });
    }

    @Test
    void resolvesDatasourceConfig() {
        AppManifest manifest = new ManifestLoader().load(exampleApp());

        assertThat(manifest.config().requireString("tesseraql.datasources.main.jdbcUrl"))
                .isEqualTo("jdbc:postgresql://localhost:5432/user_admin");
        assertThat(manifest.config().requireString("tesseraql.datasources.main.username"))
                .isEqualTo("user_admin"); // default applied when DB_USER unset
    }

    @Test
    void buildsChecksumIndex() {
        AppManifest manifest = new ManifestLoader().load(exampleApp());

        assertThat(manifest.index().aggregateHash()).isNotBlank();
        assertThat(manifest.index().fileChecksums())
                .containsKey("config/tesseraql.yml")
                .containsKey("web/api/users/get.yml");
    }

    @Test
    void parsesInputAndSqlParams() {
        AppManifest manifest = new ManifestLoader().load(exampleApp());
        RouteDefinition search = manifest.routes().stream()
                .map(RouteFile::definition)
                .filter(d -> "users.search".equals(d.id()))
                .findFirst()
                .orElseThrow();

        assertThat(search.input()).containsKey("q");
        assertThat(search.sql().file()).isEqualTo("search.sql");
        assertThat(search.sql().params()).containsEntry("q", "query.q");
        assertThat(search.security().auth()).isEqualTo("bearer");
    }

    @Test
    void loadsMcpKindsSplittingToolsResourcesAndUi(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("mcp"));
        java.nio.file.Files.writeString(dir.resolve("mcp/board.sql"), "select 1\n");
        java.nio.file.Files.writeString(dir.resolve("mcp/board.html"),
                "<section class=\"hc-card\">board</section>\n");
        // A kind: ui document: a query-html UI resource addressed by a ui:// uri, with _meta hints.
        java.nio.file.Files.writeString(dir.resolve("mcp/board.yml"), """
                version: tesseraql/v1
                id: board
                kind: ui
                recipe: query-html
                uri: ui://users/board
                description: A board of users.
                sql:
                  file: board.sql
                  mode: query
                response:
                  html:
                    template: board.html
                ui:
                  prefersBorder: true
                  csp:
                    connectDomains: ["'self'"]
                """);
        // A tool that links to the UI resource via its ui: field.
        java.nio.file.Files.writeString(dir.resolve("mcp/find.sql"), "select 1\n");
        java.nio.file.Files.writeString(dir.resolve("mcp/find.yml"), """
                version: tesseraql/v1
                id: find
                kind: tool
                recipe: query-json
                description: Find users.
                ui: ui://users/board
                sql:
                  file: find.sql
                  mode: query
                """);

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.uiResources()).singleElement().satisfies(ui -> {
            assertThat(ui.uri()).isEqualTo("ui://users/board");
            assertThat(ui.mimeType()).isEqualTo("text/html;profile=mcp-app");
            assertThat(ui.ui().prefersBorder()).isTrue();
            assertThat(ui.ui().cspConnectDomains()).containsExactly("'self'");
        });
        assertThat(manifest.tools()).singleElement()
                .satisfies(tool -> assertThat(tool.uiResource()).isEqualTo("ui://users/board"));
        assertThat(manifest.resources()).isEmpty();
    }

    @Test
    void overlayDeepMergesOverConfig(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                tenancy:
                  enabled: false
                  mode: shared-schema
                """);
        java.nio.file.Files.writeString(dir.resolve("config/overlay.yml"), """
                tenancy:
                  enabled: true
                """);

        AppManifest manifest = new ManifestLoader().load(dir);

        // Overlay overrides the leaf, deep-merge preserves the sibling key.
        assertThat(manifest.config().requireString("tenancy.enabled")).isEqualTo("true");
        assertThat(manifest.config().requireString("tenancy.mode")).isEqualTo("shared-schema");
    }
}
