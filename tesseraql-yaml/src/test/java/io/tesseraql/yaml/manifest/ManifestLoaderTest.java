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
}
