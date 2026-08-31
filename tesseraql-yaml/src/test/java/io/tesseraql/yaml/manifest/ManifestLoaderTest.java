package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

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

        // The example app chains config placeholders: tesseraql.datasources.main.jdbcUrl ->
        // ${db.main.url} -> jdbc:postgresql://${DB_HOST:localhost}:5432/user_admin. The host comes
        // from DB_HOST (the Dev Container sets it to 'db'; CI leaves it unset -> localhost), so the
        // assertions are host-independent - and check that the placeholders were actually resolved
        // (no ${...} left) so this stays deterministic across both environments.
        String jdbcUrl = manifest.config().requireString("tesseraql.datasources.main.jdbcUrl");
        assertThat(jdbcUrl).startsWith("jdbc:postgresql://").endsWith(":5432/user_admin")
                .doesNotContain("${");
        assertThat(manifest.config().requireString("tesseraql.datasources.main.username"))
                .isEqualTo(System.getenv().getOrDefault("DB_USER", "user_admin"));
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
        assertThat(search.main().file()).isEqualTo("search.sql");
        assertThat(search.main().params()).containsEntry("q", "query.q");
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
                sources:
                  main:
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
                sources:
                  main:
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
    void rejectsAnMcpDocumentWithAnUnknownKind(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("mcp"));
        java.nio.file.Files.writeString(dir.resolve("mcp/find.sql"), "select 1\n");
        // `resourse` is a typo for resource; it was silently published as a callable tool.
        java.nio.file.Files.writeString(dir.resolve("mcp/find.yml"), """
                version: tesseraql/v1
                id: find
                kind: resourse
                recipe: query-json
                description: Find users.
                uri: data://users
                sources:
                  main:
                    sql:
                      file: find.sql
                      mode: query
                """);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ManifestLoader().load(dir))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("kind: resourse");
    }

    /**
     * A prompt is a route (docs/prompt-as-recipe.md decision 1), so the loader reads it with the
     * same parser call as its three siblings and the arguments it advertises are the route's
     * {@code input:} — name, description and required, which is what an MCP prompt argument is.
     */
    @Test
    void loadsAPromptDocumentThroughTheRouteParser(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("mcp"));
        java.nio.file.Files.writeString(dir.resolve("mcp/brief.txt.tpl"), "Hello [(${who})]\n");
        java.nio.file.Files.writeString(dir.resolve("mcp/brief.yml"), """
                version: tesseraql/v1
                id: brief-user
                kind: prompt
                recipe: prompt-text
                description: Brief the model on one user.
                input:
                  name:
                    type: string
                    required: true
                    description: The user to brief on.
                security:
                  auth: bearer
                  policy: users.read
                response:
                  text:
                    template: brief.txt.tpl
                    model:
                      who: params.name
                """);

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.prompts()).singleElement().satisfies(prompt -> {
            assertThat(prompt.id()).isEqualTo("brief-user");
            assertThat(prompt.description()).isEqualTo("Brief the model on one user.");
            assertThat(prompt.definition().recipe()).isEqualTo("prompt-text");
            assertThat(prompt.definition().security().policy()).isEqualTo("users.read");
            assertThat(prompt.arguments()).singleElement().satisfies(argument -> {
                assertThat(argument.name()).isEqualTo("name");
                assertThat(argument.required()).isTrue();
                assertThat(argument.description()).isEqualTo("The user to brief on.");
            });
        });
        assertThat(manifest.tools()).isEmpty();
    }

    /**
     * A prompt with no {@code recipe:} is a document the route parser cannot read, and it says so
     * the way it says so for every other family rather than loading a prompt that renders nothing.
     */
    @Test
    void rejectsAPromptDocumentWithNoRecipe(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("mcp"));
        java.nio.file.Files.writeString(dir.resolve("mcp/brief.yml"), """
                version: tesseraql/v1
                id: brief-user
                kind: prompt
                description: Brief the model on one user.
                template: brief.txt.tpl
                """);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new ManifestLoader().load(dir))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("Missing required field 'recipe'");
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

    @Test
    void listsMigrationsPerDatasourceWithVendorOverlays(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        // Main datasource: db/migration plus a postgresql vendor overlay.
        java.nio.file.Files.createDirectories(dir.resolve("db/migration"));
        java.nio.file.Files.writeString(dir.resolve("db/migration/V1__create_items.sql"),
                "create table items (id int);\n");
        java.nio.file.Files.writeString(dir.resolve("db/migration/V2__add_email.sql"),
                "alter table items add email varchar(255);\n");
        // A non-migration file in the directory is ignored (no DDL parsing, Flyway naming only).
        java.nio.file.Files.writeString(dir.resolve("db/migration/notes.txt"), "ignore me\n");
        java.nio.file.Files.createDirectories(dir.resolve("db/migration-postgresql"));
        java.nio.file.Files.writeString(dir.resolve("db/migration-postgresql/V3__pg_index.sql"),
                "create index on items (email);\n");
        // A named datasource: db/orders/migration plus a mysql overlay.
        java.nio.file.Files.createDirectories(dir.resolve("db/orders/migration"));
        java.nio.file.Files.writeString(dir.resolve("db/orders/migration/V1__orders.sql"),
                "create table orders (id int);\n");
        java.nio.file.Files.createDirectories(dir.resolve("db/orders/migration-mysql"));
        java.nio.file.Files.writeString(dir.resolve("db/orders/migration-mysql/V2__orders_my.sql"),
                "alter table orders add note text;\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        // Sorted deterministically: by datasource, common set before vendor overlays, then version.
        assertThat(manifest.migrations())
                .extracting(MigrationFile::datasource, MigrationFile::vendor,
                        MigrationFile::version, MigrationFile::description)
                .containsExactly(
                        tuple("main", null, "1", "create_items"),
                        tuple("main", null, "2", "add_email"),
                        tuple("main", "postgresql", "3", "pg_index"),
                        tuple("orders", null, "1", "orders"),
                        tuple("orders", "mysql", "2", "orders_my"));
        // Reproducibility: the migration SQL files are part of the checksum index (full-tree walk).
        assertThat(manifest.index().fileChecksums())
                .containsKey("db/migration/V1__create_items.sql")
                .containsKey("db/migration-postgresql/V3__pg_index.sql")
                .containsKey("db/orders/migration/V1__orders.sql");
    }

    @Test
    void checksumIndexExcludesTheReservedGeneratedArtifactsDir(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        // A packaged app carries build-generated artifacts under .tesseraql/; they are derived from
        // the source, so the source checksum index must not track them.
        java.nio.file.Files.createDirectories(dir.resolve(".tesseraql/docs"));
        java.nio.file.Files.writeString(dir.resolve(".tesseraql/docs/spec.json"), "{}\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.index().fileChecksums())
                .containsKey("config/tesseraql.yml")
                .doesNotContainKey(".tesseraql/docs/spec.json");
    }

    @Test
    void checksumIndexExcludesAnEmbeddedPostgresDataDirInsideTheAppHome(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        // serve --embedded-db=./data persists a live PostgreSQL data dir under the app home. Its
        // files are non-deterministic runtime state (and on Windows the running postgres OS-locks
        // them), so the source checksum index must prune the whole subtree, marked by PG_VERSION.
        java.nio.file.Files.createDirectories(dir.resolve("data/base"));
        java.nio.file.Files.writeString(dir.resolve("data/PG_VERSION"), "16\n");
        java.nio.file.Files.writeString(dir.resolve("data/postmaster.pid"), "1234\n");
        java.nio.file.Files.writeString(dir.resolve("data/base/relation"), "binary\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.index().fileChecksums())
                .containsKey("config/tesseraql.yml")
                .doesNotContainKey("data/PG_VERSION")
                .doesNotContainKey("data/postmaster.pid")
                .doesNotContainKey("data/base/relation");
    }

    @Test
    void listsNoMigrationsWhenTheAppHasNoDbDirectory(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");

        assertThat(new ManifestLoader().load(dir).migrations()).isEmpty();
    }

    // The view registry (docs/view-composition.md wave 1): every *.view.yml under web/ and
    // templates/, indexed by app-wide-unique id.

    @Test
    void indexesViewDocumentsUnderWebAndTemplatesByTheirIds(
            @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("web/items"));
        java.nio.file.Files.writeString(dir.resolve("web/items/list.view.yml"),
                "version: tesseraql/v1\nid: items\nkind: view\nrecipe: list\n");
        java.nio.file.Files.createDirectories(dir.resolve("templates"));
        java.nio.file.Files.writeString(dir.resolve("templates/shared.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        // An unparseable document is left out (the linter reports it per document).
        java.nio.file.Files.writeString(dir.resolve("web/items/broken.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\nchrome: wide\n");

        AppManifest manifest = new ManifestLoader().load(dir);

        assertThat(manifest.views()).hasSize(2);
        assertThat(manifest.viewById("items").source())
                .isEqualTo(dir.resolve("web/items/list.view.yml"));
        // The id defaults from the file name when the document declares none.
        assertThat(manifest.viewById("shared").spec().view()).isEqualTo("list");
        assertThat(manifest.viewById("broken")).isNull();
    }

    @Test
    void aDuplicateViewIdFailsTheLoad(@org.junit.jupiter.api.io.TempDir Path dir)
            throws Exception {
        java.nio.file.Files.createDirectories(dir.resolve("config"));
        java.nio.file.Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
        java.nio.file.Files.createDirectories(dir.resolve("web/a"));
        java.nio.file.Files.createDirectories(dir.resolve("web/b"));
        java.nio.file.Files.writeString(dir.resolve("web/a/list.view.yml"),
                "version: tesseraql/v1\nid: items\nkind: view\nrecipe: list\n");
        java.nio.file.Files.writeString(dir.resolve("web/b/other.view.yml"),
                "version: tesseraql/v1\nid: items\nkind: view\nrecipe: list\n");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ManifestLoader().load(dir))
                .isInstanceOf(io.tesseraql.core.error.TqlException.class)
                .hasMessageContaining("TQL-VIEW-3315")
                .hasMessageContaining("declared twice");
    }
}
