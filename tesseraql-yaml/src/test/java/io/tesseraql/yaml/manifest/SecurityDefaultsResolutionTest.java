package io.tesseraql.yaml.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.yaml.model.SecuritySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The manifest loader stamps {@code tesseraql.security.defaults.routes} into each HTTP route
 * (docs/route-defaults.md), so every consumer of the manifest sees effective security.
 */
class SecurityDefaultsResolutionTest {

    private Path app(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    defaults:
                      routes:
                        - match: /api/**
                          auth: bearer
                        - match: /**
                          auth: browser
                          csrf: auto
                """);
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.api.search
                kind: route
                recipe: query-json
                sql:
                  file: search.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);
        Files.writeString(dir.resolve("web/api/items/search.sql"), "select 1\n");
        Files.createDirectories(dir.resolve("web/items/create"));
        Files.writeString(dir.resolve("web/items/create/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                security:
                  policy: items.write
                steps:
                  insert:
                    file: insert.sql
                response:
                  json:
                    body:
                      ok: "true"
                """);
        Files.writeString(dir.resolve("web/items/create/insert.sql"), "select 1\n");
        return dir;
    }

    @Test
    void defaultsFillRoutesThatDeclareNothing(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir));

        SecuritySpec api = route(manifest, "items.api.search");
        assertThat(api.auth()).isEqualTo("bearer");
        assertThat(api.csrf()).isNull();
    }

    @Test
    void defaultsMergePerKeyUnderDeclaredKeys(@TempDir Path dir) throws Exception {
        AppManifest manifest = new ManifestLoader().load(app(dir));

        // The browser write declares only its policy: auth and csrf (auto -> POST) come
        // from the matched /** rule.
        SecuritySpec create = route(manifest, "items.create");
        assertThat(create.auth()).isEqualTo("browser");
        assertThat(create.policy()).isEqualTo("items.write");
        assertThat(create.csrf()).isTrue();
    }

    @Test
    void webhookRoutesKeepTheirSignatureContract(@TempDir Path dir) throws Exception {
        Path home = app(dir);
        Files.createDirectories(home.resolve("web/hooks/inventory"));
        Files.writeString(home.resolve("web/hooks/inventory/post.yml"), """
                version: tesseraql/v1
                id: hooks.inventory
                kind: route
                recipe: webhook
                webhook:
                  provider: inventory
                steps:
                  record:
                    file: record.sql
                """);
        Files.writeString(home.resolve("web/hooks/inventory/record.sql"), "select 1\n");

        AppManifest manifest = new ManifestLoader().load(home);

        // Deliveries authenticate by signature (the verifier), never by a defaulted principal.
        assertThat(manifest.routes().stream()
                .filter(route -> "hooks.inventory".equals(route.definition().id()))
                .findFirst().orElseThrow().definition().security()).isNull();
    }

    private static SecuritySpec route(AppManifest manifest, String id) {
        return manifest.routes().stream()
                .filter(route -> id.equals(route.definition().id()))
                .findFirst().orElseThrow().definition().security();
    }
}
