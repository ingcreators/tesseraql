package io.tesseraql.yaml.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The eject action (customization ladder L3, docs/declarative-views.md). */
class ViewEjectorTest {

    private static ViewSpec parse(Path dir, String yaml) throws Exception {
        Path file = dir.resolve("page.view.yml");
        Files.writeString(file, yaml);
        return ViewSpec.parse(file);
    }

    @Test
    void ejectsAListWithPinnedColumnsLinksAndSlot(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/frags.html"),
                "<a th:fragment=\"newLink\" href=\"/new\">New</a>");
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Items
                columns:
                  - name: name
                    link: /items/{id}
                  - name: status
                slots:
                  header: frags.html::newLink
                """);
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/items/page.html");
        assertThat(file.path()).isEqualTo("web/items/page.html");
        assertThat(file.content()).contains("th:each=\"row : ${main.rows}\"");
        assertThat(file.content()).contains("th:href=\"|/items/${row['id']}|\"");
        assertThat(file.content()).contains("th:text=\"${row['status']}\"");
        assertThat(file.content()).contains(">Status</th>");
        assertThat(file.content()).contains("~{templates/frags.html :: newLink}");
        assertThat(file.content()).contains("tql/shell :: shell('Items'");
    }

    @Test
    void ejectsJapaneseColumnsAndLinkPlaceholders(@TempDir Path dir) throws Exception {
        // An ASCII-only placeholder pattern left {受注番号} as literal braces in the
        // ejected href (docs/unicode-identifiers.md).
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: 受注一覧
                columns:
                  - name: 受注番号
                    link: /受注/{受注番号}
                  - name: 状態
                """);
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/受注/page.html");
        assertThat(file.content()).contains("th:href=\"|/受注/${row['受注番号']}|\"");
        assertThat(file.content()).contains("th:text=\"${row['状態']}\"");
    }

    @Test
    void ejectsACatalogResolutionForACodedColumn(@TempDir Path dir) throws Exception {
        // docs/lookups.md decision 8: ejecting pins the layout, never the data — the ejected
        // page calls the catalog rather than carrying today's names as literals, on a list
        // column, on a detail field, and inside a child table alike.
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/codes.yml"), """
                version: tesseraql/v1
                domains:
                  取引区分:
                    type: string
                    codes: 取引区分
                """);
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: detail
                fields:
                  - name: 取引区分
                    domain: 取引区分
                children:
                  - source: 履歴
                    columns:
                      - name: 取引区分
                        domain: 取引区分
                      - name: 金額
                """);
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/受注/page.html");
        assertThat(file.content())
                .contains("${row == null ? '' : codes['取引区分'].of(row['取引区分'])}")
                .contains("${codes['取引区分'].of(child['取引区分'])}")
                // A column with no domain: keeps reading the row directly.
                .contains("${child['金額']}");
    }

    @Test
    void aListWithoutExplicitColumnsRefusesToEject(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        assertThatThrownBy(() -> ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/items/page.html"))
                .isInstanceOf(TqlException.class).hasMessageContaining("explicit columns");
    }

    @Test
    void ejectsADetailWithChildren(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: detail
                fields:
                  - name: name
                    label: Name
                children:
                  - source: orders
                    title: Orders
                    columns:
                      - name: qty
                """);
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/items/page.html");
        assertThat(file.content()).contains("row=${#lists.isEmpty(main.rows)");
        assertThat(file.content()).contains("${row == null ? '' : row['name']}");
        assertThat(file.content()).contains(">Orders</h3>");
        assertThat(file.content()).contains("th:each=\"child : ${orders.rows}\"");
        assertThat(file.content()).contains("${child['qty']}");
    }

    @Test
    void ejectsAFormFromItsDerivedFields(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                id: items.new
                action: /items/create
                """);
        List<ViewFields.FieldDef> fields = List.of(
                new ViewFields.FieldDef("name", "k", "Name", "text", true, 200, null, null,
                        List.of(), null, null, null, null),
                new ViewFields.FieldDef("status", "k", "Status", "select", false, null, null,
                        null, List.of("OPEN", "CLOSED"), null, null, null, null));
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, fields,
                "web/items/new/page.html");
        assertThat(file.content()).contains("hx-post=\"/items/create\"");
        assertThat(file.content()).contains("id=\"items-new-form\"");
        assertThat(file.content()).contains("name=\"name\" required maxlength=\"200\"");
        assertThat(file.content()).contains("<option value=\"OPEN\"")
                .contains(">OPEN</option>");
        assertThat(file.content()).contains("th:text=\"#{tql.view.submit}\"");
    }

    /**
     * An apostrophe ends the single-quoted OGNL literal the selected-option expression splices
     * values into; {@code Escapes.html} never touches it, so an option like {@code O'Brien}
     * broke the ejected template at parse time — and, read as injection, let a view spec splice
     * into the expression. The title rides the same grammar in the shell call.
     */
    @Test
    void anApostropheStaysInsideItsExpressionLiteral(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                id: people.new
                title: Bob's people
                action: /people/create
                """);
        List<ViewFields.FieldDef> fields = List.of(
                new ViewFields.FieldDef("owner", "k", "Owner", "select", false, null, null,
                        null, List.of("O'Brien"), null, null, null, null));

        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, fields,
                "web/people/new/page.html");

        assertThat(file.content()).contains("== 'O\\'Brien'");
        assertThat(file.content()).contains("<option value=\"O'Brien\"");
        assertThat(file.content()).contains("shell('Bob\\'s people'");
    }

    @Test
    void ejectsADashboardWithAllPanelKinds(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                title: Stats
                panels:
                  - title: Users
                    type: stat
                    column: user_count
                  - title: By status
                    type: chart
                    chart: bar
                    source: byStatus
                    x: status
                    y: n
                  - title: Trend
                    type: sparkline
                    source: byStatus
                    column: n
                  - title: Latest
                    type: table
                    source: recent
                    columns:
                      - name: name
                """);

        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/stats/page.html");

        assertThat(file.content()).contains("hc-grid")
                .contains("${#lists.isEmpty(main.rows) ? '—' : main.rows[0]['user_count']}")
                .contains("data-hc-chart=\"bar\"")
                .contains("th:each=\"row : ${byStatus.rows}\"")
                .contains("#strings.listJoin(byStatus.rows.{n}, ',')")
                .contains("th:each=\"row : ${recent.rows}\"")
                .contains("charts.js");
    }

    @Test
    void flipRouteSwapsViewForTemplate() {
        String yaml = """
                response:
                  html:
                    view: page.view.yml
                    headers:
                      X: y
                """;
        String flipped = ViewEjector.flipRoute(yaml, "page.view.yml", "page.html");
        assertThat(flipped).contains("    template: page.html");
        assertThat(flipped).doesNotContain("view: page.view.yml");
        assertThat(flipped).contains("X: y");
    }

    @Test
    void flipRouteFailsWhenTheViewLineIsMissing() {
        assertThatThrownBy(() -> ViewEjector.flipRoute("response: {}", "page.view.yml", "x.html"))
                .isInstanceOf(TqlException.class).hasMessageContaining("cannot flip");
    }
}
