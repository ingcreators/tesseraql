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
                kind: view
                view: list
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
        assertThat(file.content()).contains("th:each=\"row : ${sql.rows}\"");
        assertThat(file.content()).contains("th:href=\"|/items/${row['id']}|\"");
        assertThat(file.content()).contains("th:text=\"${row['status']}\"");
        assertThat(file.content()).contains(">Status</th>");
        assertThat(file.content()).contains("~{templates/frags.html :: newLink}");
        assertThat(file.content()).contains("tql/shell :: shell('Items'");
    }

    @Test
    void aListWithoutExplicitColumnsRefusesToEject(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, "kind: view\nview: list\n");
        assertThatThrownBy(() -> ViewEjector.eject(dir, dir, "page.view.yml", spec, List.of(),
                "web/items/page.html"))
                .isInstanceOf(TqlException.class).hasMessageContaining("explicit columns");
    }

    @Test
    void ejectsADetailWithChildren(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                kind: view
                view: detail
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
        assertThat(file.content()).contains("row=${#lists.isEmpty(sql.rows)");
        assertThat(file.content()).contains("${row == null ? '' : row['name']}");
        assertThat(file.content()).contains(">Orders</h3>");
        assertThat(file.content()).contains("th:each=\"child : ${orders.rows}\"");
        assertThat(file.content()).contains("${child['qty']}");
    }

    @Test
    void ejectsAFormFromItsDerivedFields(@TempDir Path dir) throws Exception {
        ViewSpec spec = parse(dir, """
                kind: view
                view: form
                id: items.new
                action: /items/create
                """);
        List<ViewFields.FieldDef> fields = List.of(
                new ViewFields.FieldDef("name", "k", "Name", "text", true, 200, null, null,
                        List.of(), null, null),
                new ViewFields.FieldDef("status", "k", "Status", "select", false, null, null,
                        null, List.of("OPEN", "CLOSED"), null, null));
        ScaffoldedFile file = ViewEjector.eject(dir, dir, "page.view.yml", spec, fields,
                "web/items/new/page.html");
        assertThat(file.content()).contains("hx-post=\"/items/create\"");
        assertThat(file.content()).contains("id=\"items-new-form\"");
        assertThat(file.content()).contains("name=\"name\" required maxlength=\"200\"");
        assertThat(file.content()).contains("<option value=\"OPEN\"")
                .contains(">OPEN</option>");
        assertThat(file.content()).contains("th:text=\"#{tql.view.submit}\"");
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
