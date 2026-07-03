package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for declarative views (roadmap Phase 39, {@code TQL-VIEW-33xx}). */
class AppLinterViewTest {

    /** A view-backed list page, its data SQL, and the POST action route a form derives from. */
    private static void writeApp(Path dir, String viewYaml) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id, name from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sql:
                  file: list.sql
                response:
                  html:
                    view: items.view.yml
                """);
        Files.writeString(dir.resolve("web/items/items.view.yml"), viewYaml);
        Files.createDirectories(dir.resolve("web/items/create"));
        Files.writeString(dir.resolve("web/items/create/insert.sql"),
                "insert into items (name) values (/* name */ 'x')\n");
        Files.writeString(dir.resolve("web/items/create/post.yml"), """
                version: tesseraql/v1
                id: items.create
                kind: route
                recipe: command-json
                input:
                  name: { type: string, required: true, maxLength: 200 }
                sql:
                  file: insert.sql
                  mode: update
                  params:
                    name: params.name
                """);
    }

    private static List<String> viewCodes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code).filter(c -> c.startsWith("TQL-VIEW"))
                .toList();
    }

    @Test
    void aWellFormedListViewProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: list\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aWellFormedFormViewProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: form
                action: /items/create
                fields:
                  - name: name
                    widget: textarea
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void viewAndTemplateTogetherAreAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: list\n");
        Files.writeString(dir.resolve("web/items/index.html"), "<p>x</p>");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sql:
                  file: list.sql
                response:
                  html:
                    template: index.html
                    view: items.view.yml
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void anUnresolvedViewFileIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: list\n");
        Files.delete(dir.resolve("web/items/items.view.yml"));
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void anInvalidViewDocumentIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: wizard\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3301");
    }

    @Test
    void aFormActionMatchingNoPostRouteIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: form\naction: /nowhere\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3303");
    }

    @Test
    void aFieldTheActionDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: form
                action: /items/create
                fields:
                  - name: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3304");
    }

    @Test
    void anUnknownWidgetIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: form
                action: /items/create
                fields:
                  - name: name
                    widget: carousel
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3305");
    }

    @Test
    void anUnknownSlotNameIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: list
                slots:
                  sidebar: frags.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3306");
    }

    @Test
    void anUnresolvedSlotReferenceIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: list
                slots:
                  header: missing.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void aResolvedSlotIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: list
                slots:
                  header: frags.html::newLink
                """);
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/frags.html"),
                "<a th:fragment=\"newLink\" href=\"/x\">x</a>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aChildSourceTheRouteDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                kind: view
                view: detail
                children:
                  - source: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3308");
    }

    @Test
    void anOverrideWithoutTheFragmentSignatureIsAWarning(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"), "<form></form>");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(viewCodes(findings)).contains("TQL-VIEW-3307");
        assertThat(findings.stream().filter(f -> f.code().equals("TQL-VIEW-3307")).findFirst()
                .orElseThrow().severity()).isEqualTo("warning");
    }

    @Test
    void anOverrideWithTheFragmentSignatureIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "kind: view\nview: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"),
                "<form th:fragment=\"view(v)\"></form>");
        Files.writeString(dir.resolve("templates/tql/view/field-date.html"),
                "<div th:fragment=\"field(f)\"></div>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }
}
