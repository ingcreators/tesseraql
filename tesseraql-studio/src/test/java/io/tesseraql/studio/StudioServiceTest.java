package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.StudioService.Explorer;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudioServiceTest {

    private static AppManifest exampleManifest() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void explorerListsRoutesAndJobs() {
        Explorer explorer = new StudioService(exampleManifest(), true).explorer();

        assertThat(explorer.readOnly()).isTrue();
        assertThat(explorer.routes()).anySatisfy(route -> {
            assertThat(route.id()).isEqualTo("users.search");
            assertThat(route.method()).isEqualTo("GET");
            assertThat(route.path()).isEqualTo("/api/users");
            assertThat(route.source()).isEqualTo("web/api/users/get.yml");
        });
        assertThat(explorer.jobs()).anyMatch(job -> job.id().equals("user.dailyMaintenance"));
    }

    @Test
    void readsSourceFileAndRejectsTraversal() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThat(studio.source("web/api/users/search.sql")).contains("select");

        assertThatThrownBy(() -> studio.source("../../etc/passwd"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes app home");
    }

    @Test
    void readOnlyModeRejectsDraftSaves() {
        StudioService studio = new StudioService(exampleManifest(), true);

        assertThatThrownBy(() -> studio.saveDraft("web/api/users/get.yml", "edited"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void writableModeSavesAndReadsDrafts(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/x"));
        Files.writeString(dir.resolve("web/api/x/get.yml"), """
                version: tesseraql/v1
                id: x
                kind: route
                recipe: query-json
                sql:
                  file: x.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """);

        StudioService studio = new StudioService(new ManifestLoader().load(dir), false);
        studio.saveDraft("web/api/x/get.yml", "version: tesseraql/v1\nid: x-edited\n");

        assertThat(studio.readDraft("web/api/x/get.yml")).contains("x-edited");
        // The draft does not touch the source of truth.
        assertThat(studio.source("web/api/x/get.yml")).contains("id: x\n");
    }
}
