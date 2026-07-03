package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import io.tesseraql.yaml.model.RouteDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Declarative views rendered through the {@code tql/view/*} pattern fragments (roadmap Phase 39,
 * docs/declarative-views.md): the list datagrid over live rows, the form derived from the action
 * route's {@code input:} block, and the customization-ladder L2 pattern override.
 */
class HtmlResponseRendererViewTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A POST action route with the input: block a form view derives its fields from. */
    private static RouteDefinition actionRoute() {
        return MAPPER.convertValue(Map.of(
                "id", "items.create",
                "kind", "route",
                "recipe", "command-json",
                "input", Map.of(
                        "name", Map.of("type", "string", "required", true, "maxLength", 200),
                        "quantity", Map.of("type", "integer", "min", 0),
                        "status", Map.of("type", "string", "enum", List.of("OPEN", "CLOSED")),
                        "active", Map.of("type", "boolean"))),
                RouteDefinition.class);
    }

    private static HtmlResponseRenderer renderer(Path dir, String viewYaml) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), viewYaml);
        ViewBinding binding = ViewBinding.of(dir, dir, "page.view.yml",
                path -> "/items/create".equals(path) ? actionRoute() : null);
        return new HtmlResponseRenderer(new HtmlResponse(200, null, "page.view.yml",
                Map.of(), Map.of(), Map.of()), dir, dir, "en", binding);
    }

    private static String render(HtmlResponseRenderer renderer, Map<String, Object> context)
            throws Exception {
        Exchange exchange = new DefaultExchange(new DefaultCamelContext());
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        renderer.process(exchange);
        return exchange.getMessage().getBody(String.class);
    }

    @Test
    void aListViewRendersTheQuerysOwnColumnsAsADatagrid(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: list
                title: Items
                """);
        String html = render(renderer, Map.of("sql", Map.of("rows", List.of(
                Map.of("id", 1, "name", "Bolt"),
                Map.of("id", 2, "name", "Nut")))));
        assertThat(html).contains("hc-datagrid__table");
        // Derived columns render in the row's own order with humanized labels.
        assertThat(html).contains(">Id</th>").contains(">Name</th>");
        assertThat(html).contains(">Bolt<").contains(">Nut<");
    }

    @Test
    void aListColumnLinkResolvesPerRow(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: list
                columns:
                  - name: name
                    link: /items/{id}
                """);
        String html = render(renderer, Map.of("sql", Map.of("rows", List.of(
                Map.of("id", 7, "name", "Bolt")))));
        assertThat(html).contains("href=\"/items/7\"").contains(">Bolt</a>");
    }

    @Test
    void anEmptyListRendersTheEmptyMessage(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, "kind: view\nview: list\n");
        String html = render(renderer, Map.of());
        assertThat(html).contains("No rows");
    }

    @Test
    void aFormViewDerivesItsFieldsFromTheActionRoutesInputBlock(@TempDir Path dir)
            throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: form
                title: New item
                action: /items/create
                """);
        String html = render(renderer, Map.of());
        assertThat(html).contains("hx-post=\"/items/create\"");
        // The string input carries the same constraints InputBinder enforces server-side.
        assertThat(html).contains("name=\"name\"").contains("required")
                .contains("maxlength=\"200\"");
        // integer -> number widget with min; enum -> select with its options; boolean -> checkbox.
        assertThat(html).contains("type=\"number\"").contains("min=\"0\"");
        assertThat(html).contains("<select").contains(">OPEN<").contains(">CLOSED<");
        assertThat(html).contains("type=\"checkbox\"");
        assertThat(html).contains(">Save</button>");
    }

    @Test
    void fieldsEntriesSelectOrderAndOverride(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: form
                action: /items/create
                fields:
                  - name: name
                    widget: textarea
                    label: Item name
                """);
        String html = render(renderer, Map.of());
        assertThat(html).contains("<textarea").contains(">Item name</label>");
        // Unselected inputs are not rendered.
        assertThat(html).doesNotContain("name=\"quantity\"");
    }

    @Test
    void anAppOverrideOfThePatternWinsOverTheClasspathFragment(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir.resolve("templates/tql/view"));
        Files.writeString(dir.resolve("templates/tql/view/list.html"),
                "<p th:fragment=\"view(v)\" th:text=\"'custom:' + ${v.title}\"></p>");
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: list
                title: Items
                """);
        String html = render(renderer, Map.of());
        assertThat(html).contains("custom:Items");
        assertThat(html).doesNotContain("hc-datagrid");
    }

    @Test
    void viewAndTemplateTogetherFailTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), "kind: view\nview: list\n");
        Files.writeString(dir.resolve("index.html"), "<p>x</p>");
        ViewBinding binding = ViewBinding.of(dir, dir, "page.view.yml", path -> null);
        assertThatThrownBy(() -> new HtmlResponseRenderer(
                new HtmlResponse(200, "index.html", "page.view.yml", Map.of(), Map.of(), Map.of()),
                dir, dir, "en", binding))
                .isInstanceOf(TqlException.class).hasMessageContaining("mutually exclusive");
    }

    @Test
    void aFormActionMatchingNoPostRouteFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "kind: view\nview: form\naction: /nowhere\n");
        assertThatThrownBy(() -> ViewBinding.of(dir, dir, "page.view.yml", path -> null))
                .isInstanceOf(TqlException.class).hasMessageContaining("matches no POST route");
    }
}
