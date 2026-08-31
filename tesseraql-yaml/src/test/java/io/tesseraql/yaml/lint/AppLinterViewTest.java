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
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  html:
                    view: items
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
                steps:
                  - id: main
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
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aPlainColumnLinkPlaceholderProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{id}/lines/{line_no}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aFilterMustNameADeclaredRouteInput(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                filters: [ghost]
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3323");
    }

    @Test
    void aPresetParamMustNameADeclaredRouteInput(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                presets:
                  - name: Ghosts
                    params: { ghost: "1" }
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3324");
    }

    @Test
    void aPresetMayPinTheFrameworkSortParams(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                presets:
                  - name: Newest
                    params: { sort: name, dir: desc }
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aBulkActionMustMatchAPostRoute(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                actions:
                  - label: Ghost
                    action: /items/ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3325");
    }

    @Test
    void aBulkActionTargetingARealPostRouteLintsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                actions:
                  - label: Create-ish
                    action: /items/create
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aDottedLinkPlaceholderIsAnError(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 3: the runtime renders a dotted path but the ejector
        // rewrites placeholders per column — the divergence is refused at lint time.
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{row.id}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3321");
    }

    @Test
    void aMalformedLinkPlaceholderIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{}
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3321");
    }

    @Test
    void aWellFormedFormViewProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: name
                    widget: textarea
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void viewAndTemplateTogetherAreAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.writeString(dir.resolve("web/items/index.html"), "<p>x</p>");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  html:
                    template: index.html
                    view: items
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void anUnresolvedViewFileIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
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
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: form\naction: /nowhere\n");
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3303");
    }

    @Test
    void aFieldTheActionDoesNotDeclareIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3304");
    }

    @Test
    void anUnknownWidgetIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
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
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  sidebar: frags.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3306");
    }

    @Test
    void anUnresolvedSlotReferenceIsAnError(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  header: missing.html::x
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3302");
    }

    @Test
    void aResolvedSlotIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
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
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - source: ghost
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).contains("TQL-VIEW-3308");
    }

    @Test
    void aColumnDomainReferenceIsCheckedPerDocument(@TempDir Path dir) throws Exception {
        // docs/view-composition.md wave 3a: explicit read-side domain links must resolve.
        writeApp(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - { name: sku, domain: ghost }
                """);
        java.util.List<String> codes = new AppLinter().lint(dir).stream()
                .map(LintFinding::code).toList();
        assertThat(codes).contains("TQL-FIELD-4601");

        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  ghost:
                    type: string
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aChildSourceNamingAnHttpSourceIsClean(@TempDir Path dir) throws Exception {
        // docs/connectors.md http sources publish the same {rows} shape as a named query,
        // so a child/panel source: may name one (docs/view-composition.md wave 0).
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select id, name from items\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.page
                kind: route
                recipe: query-html
                sources:
                  main:
                    sql:
                      file: list.sql
                  rates:
                    http:
                      url: ${tesseraql.connectors.fx.baseUrl}/v1/rates
                response:
                  html:
                    view: items
                """);
        Files.writeString(dir.resolve("web/items/items.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - source: rates
                """);
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void anOverrideWithoutTheFragmentSignatureIsAWarning(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"), "<form></form>");
        List<LintFinding> findings = new AppLinter().lint(dir);
        assertThat(viewCodes(findings)).contains("TQL-VIEW-3307");
        assertThat(findings.stream().filter(f -> f.code().equals("TQL-VIEW-3307")).findFirst()
                .orElseThrow().severity()).isEqualTo("warning");
    }

    @Test
    void anOverrideWithTheFragmentSignatureIsClean(@TempDir Path dir) throws Exception {
        writeApp(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/form.html"),
                "<form th:fragment=\"view(v)\"></form>");
        Files.writeString(dir.resolve("templates/tql/view/field-date.html"),
                "<div th:fragment=\"field(f)\"></div>");
        assertThat(viewCodes(new AppLinter().lint(dir))).isEmpty();
    }
}
