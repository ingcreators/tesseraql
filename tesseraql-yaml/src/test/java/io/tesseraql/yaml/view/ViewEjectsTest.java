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

    /**
     * The lock reaches the ejected form (docs/edit-conflict.md decision 3). Without this the
     * wiring could pass a hard-coded null and every other test would stay green.
     */
    @Test
    void anEjectedFormCarriesTheActionRouteDeclaredLock(@TempDir Path tmp) throws IOException {
        Path app = copyHelpdesk(tmp);
        Path action = app.resolve("web/tickets/new/post.yml");
        Files.writeString(action, Files.readString(action) + "\nlock: version\n");

        ViewEjects.Result result = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/new/get.yml", false);

        assertThat(result.blocked()).isFalse();
        assertThat(Files.readString(app.resolve(result.templatePath())))
                .contains("name=\"_lock\"")
                .contains("row['version']");
    }

    /**
     * And the other direction: an unlocked action route ejects no lock field. Without this,
     * the wiring could hard-code a constant column and stay green.
     */
    @Test
    void anEjectedFormWithoutADeclaredLockCarriesNoLockField(@TempDir Path tmp)
            throws IOException {
        Path app = copyHelpdesk(tmp);

        ViewEjects.Result result = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/new/get.yml", false);

        assertThat(Files.readString(app.resolve(result.templatePath())))
                .doesNotContain("name=\"_lock\"")
                .contains("name=\"_idempotency\"");
    }

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
        assertThat(route).contains("template: list.html").doesNotContain("view: tickets");

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
                .contains("view: tickets");

        ViewEjects.Result forced = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", true);

        assertThat(forced.blocked()).isFalse();
        assertThat(Files.readString(app.resolve("web/tickets/list.html")))
                .contains("tql/shell :: shell");
        assertThat(Files.readString(app.resolve("web/tickets/get.yml")))
                .contains("template: list.html");
    }

    @Test
    void refusesToEjectASharedView(@TempDir Path tmp) throws IOException {
        // TQL-VIEW-3316 (docs/view-composition.md wave 1): flipping one route would fork
        // rendering for the other referencing routes silently.
        Path app = copyHelpdesk(tmp);
        Files.createDirectories(app.resolve("web/archive"));
        Files.writeString(app.resolve("web/archive/get.yml"), """
                version: tesseraql/v1
                id: tickets.archive
                kind: route
                recipe: page
                security: { auth: browser, policy: help.agent }
                response:
                  html:
                    view: tickets
                """);

        assertThatThrownBy(() -> ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/tickets/get.yml", false))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3316")
                .hasMessageContaining("shared by 2 routes")
                .hasMessageContaining("web/archive/get.yml");
    }

    @Test
    void aCompositeEjectKeepsEmbeddedViewsDeclarative(@TempDir Path tmp) throws IOException {
        // docs/view-composition.md wave 2c: the host layout pins; each embedded view stays a
        // document, rendered through the flipped route's views: binding.
        Path app = copyHelpdesk(tmp);
        Files.writeString(app.resolve("web/tickets/recent.view.yml"), """
                version: tesseraql/v1
                id: tickets.recent
                kind: view
                recipe: list
                title: Recent tickets
                """);
        Files.createDirectories(app.resolve("web/overview"));
        Files.writeString(app.resolve("web/overview/board.view.yml"), """
                version: tesseraql/v1
                id: tickets.overview
                kind: view
                recipe: dashboard
                title: Overview
                panels:
                  - { type: view, view: tickets.recent }
                """);
        Files.writeString(app.resolve("web/overview/get.yml"), """
                version: tesseraql/v1
                id: tickets.overview.page
                kind: route
                recipe: query-html
                security: { auth: browser, policy: help.agent }
                sources:
                  main:
                    sql:
                      file: ../tickets/list.sql
                response:
                  html:
                    view: tickets.overview
                """);

        ViewEjects.Result result = ViewEjects.eject(app, new ManifestLoader().load(app),
                "web/overview/get.yml", false);

        assertThat(result.blocked()).isFalse();
        String template = Files.readString(app.resolve("web/overview/board.html"));
        assertThat(template)
                .contains("~{tql/view/list :: view(${views['tickets.recent']})}");
        String route = Files.readString(app.resolve("web/overview/get.yml"));
        assertThat(route).contains("template: board.html")
                .contains("views: [tickets.recent]");
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
