package io.tesseraql.yaml.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The shared eject orchestration (docs/page-builder.md D2), exercised against the real
 * helpdesk example: template written where the view lived, route flipped, and the
 * blocked path (hand-edited target, no force) changes nothing.
 */
class ViewEjectsTest {

    @Test
    void ejectsTheHelpdeskListViewAndFlipsTheRoute(@TempDir Path tmp) throws IOException {
        Path app = copyHelpdesk(tmp);

        ViewEjects.Result result = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", false);

        assertThat(result.blocked()).isFalse();
        assertThat(result.templatePath()).isEqualTo("web/tickets/list.html");
        String template = Files.readString(app.resolve("web/tickets/list.html"));
        assertThat(template).contains("tql/shell :: shell").contains("id=\"page-content\"")
                .contains("hc-datagrid").contains("Ejected from list.view.yml");
        String route = Files.readString(app.resolve("web/tickets/get.yml"));
        assertThat(route).contains("template: list.html").doesNotContain("view: list.view.yml");

        // A second eject finds no view: to flip.
        assertThatThrownBy(() -> ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", false))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("no response.html.view");
    }

    @Test
    void blockedTargetChangesNothingUntilForced(@TempDir Path tmp) throws IOException {
        Path app = copyHelpdesk(tmp);
        Files.writeString(app.resolve("web/tickets/list.html"), "<p>hand-authored</p>\n");

        ViewEjects.Result blocked = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", false);

        assertThat(blocked.blocked()).isTrue();
        assertThat(Files.readString(app.resolve("web/tickets/list.html")))
                .isEqualTo("<p>hand-authored</p>\n");
        assertThat(Files.readString(app.resolve("web/tickets/get.yml")))
                .contains("view: list.view.yml");

        ViewEjects.Result forced = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", true);

        assertThat(forced.blocked()).isFalse();
        assertThat(Files.readString(app.resolve("web/tickets/list.html")))
                .contains("tql/shell :: shell");
        assertThat(Files.readString(app.resolve("web/tickets/get.yml")))
                .contains("template: list.html");
    }

    @Test
    void unknownRouteAndViewlessRouteThrow(@TempDir Path tmp) throws IOException {
        Path app = copyHelpdesk(tmp);

        assertThatThrownBy(() -> ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/nope/get.yml", false))
                .isInstanceOf(TqlException.class).hasMessageContaining("No route at");
    }

    private static Path copyHelpdesk(Path tmp) throws IOException {
        Path source = Paths.get("..", "examples", "helpdesk-app").toAbsolutePath().normalize();
        Path target = tmp.resolve("helpdesk-app");
        try (Stream<Path> files = Files.walk(source)) {
            files.forEach(path -> {
                try {
                    Path to = target.resolve(source.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(to);
                    } else {
                        Files.createDirectories(to.getParent());
                        Files.copy(path, to);
                    }
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
        return target;
    }
}
