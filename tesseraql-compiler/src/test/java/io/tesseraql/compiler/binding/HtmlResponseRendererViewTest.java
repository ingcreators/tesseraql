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
        return renderer(dir, viewYaml, null);
    }

    private static HtmlResponseRenderer renderer(Path dir, String viewYaml,
            RouteDefinition route) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), viewYaml);
        ViewBinding binding = ViewBinding.of(dir, dir, "page.view.yml", route,
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
        assertThat(html).contains(">Id</span>").contains(">Name</span>");
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
        ViewBinding binding = ViewBinding.of(dir, dir, "page.view.yml", null, path -> null);
        assertThatThrownBy(() -> new HtmlResponseRenderer(
                new HtmlResponse(200, "index.html", "page.view.yml", Map.of(), Map.of(), Map.of()),
                dir, dir, "en", binding))
                .isInstanceOf(TqlException.class).hasMessageContaining("mutually exclusive");
    }

    @Test
    void aFormActionMatchingNoPostRouteFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "kind: view\nview: form\naction: /nowhere\n");
        assertThatThrownBy(() -> ViewBinding.of(dir, dir, "page.view.yml", null, path -> null))
                .isInstanceOf(TqlException.class).hasMessageContaining("matches no POST route");
    }

    @Test
    void aDetailViewRendersLabelledValuesAndChildren(@TempDir Path dir) throws Exception {
        // The declaring route carries a named query the child composes under the parent row.
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "items.detail",
                "kind", "route",
                "recipe", "query-html",
                "queries", Map.of("orders", Map.of("file", "orders.sql"))),
                RouteDefinition.class);
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: detail
                title: Item
                fields:
                  - name: name
                  - name: status
                    label: State
                children:
                  - source: orders
                    title: Orders
                    columns:
                      - name: qty
                """, route);
        String html = render(renderer, Map.of(
                "sql", Map.of("rows", List.of(Map.of("name", "Bolt", "status", "OPEN"))),
                "orders", Map.of("rows", List.of(Map.of("qty", 3), Map.of("qty", 5)))));
        assertThat(html).contains(">Name</span>").contains(">Bolt</span>");
        assertThat(html).contains(">State</span>").contains(">OPEN</span>");
        assertThat(html).contains(">Orders</h3>").contains(">3</span>").contains(">5</span>");
    }

    @Test
    void aChildSourceTheRouteDoesNotDeclareFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                kind: view
                view: detail
                children:
                  - source: ghost
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, dir, "page.view.yml", null, path -> null))
                .isInstanceOf(TqlException.class).hasMessageContaining("ghost");
    }

    @Test
    void aSlotFillsFromTheAppFragment(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/frags.html"),
                "<a th:fragment=\"newLink\" href=\"/items/new\">New item</a>");
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: list
                title: Items
                slots:
                  header: frags.html::newLink
                """);
        String html = render(renderer, Map.of());
        assertThat(html).contains("href=\"/items/new\"").contains(">New item</a>");
    }

    @Test
    void anUnknownSlotNameFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                kind: view
                view: list
                slots:
                  sidebar: frags.html::x
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, dir, "page.view.yml", null, path -> null))
                .isInstanceOf(TqlException.class).hasMessageContaining("unknown slot");
    }

    @Test
    void aDashboardRendersStatSparklineChartAndTablePanels(@TempDir Path dir) throws Exception {
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "stats",
                "kind", "route",
                "recipe", "query-html",
                "queries", Map.of(
                        "totals", Map.of("file", "totals.sql"),
                        "signups", Map.of("file", "signups.sql"))),
                RouteDefinition.class);
        HtmlResponseRenderer renderer = renderer(dir, """
                kind: view
                view: dashboard
                title: Stats
                panels:
                  - title: Users
                    type: stat
                    source: totals
                    column: user_count
                  - title: Signups
                    type: chart
                    source: signups
                    x: day
                    y: n
                  - title: Trend
                    type: sparkline
                    source: signups
                    column: n
                  - title: Latest
                    type: table
                    source: signups
                """, route);
        String html = render(renderer, Map.of(
                "totals", Map.of("rows", List.of(Map.of("user_count", 42))),
                "signups", Map.of("rows", List.of(
                        Map.of("day", "Mon", "n", 2),
                        Map.of("day", "Tue", "n", 5)))));
        assertThat(html).contains("class=\"hc-grid\"");
        assertThat(html).contains(">42</strong>");
        assertThat(html).contains("class=\"hc-sparkline\"").contains("data-values=\"2,5\"")
                .contains("data-max=\"5\"");
        assertThat(html).contains("<figure class=\"hc-chart\">")
                .contains("hc-chart__plot").contains("fill=\"var(--hc-chart-series-1)\"");
        assertThat(html).contains("hc-datagrid__table").contains(">Tue</span>");
    }

    @Test
    void aDashboardPanelSourceTheRouteDoesNotDeclareFailsTheBuild(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                kind: view
                view: dashboard
                panels:
                  - type: stat
                    source: ghost
                    column: c
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, dir, "page.view.yml", null, path -> null))
                .isInstanceOf(TqlException.class).hasMessageContaining("panel source ghost");
    }

    @Test
    void anEjectedListTemplateRendersTheSameRows(@TempDir Path dir) throws Exception {
        // L3: the generated template is real Thymeleaf that renders without the view machinery.
        Files.writeString(dir.resolve("page.view.yml"), """
                kind: view
                view: list
                title: Items
                columns:
                  - name: name
                    link: /items/{id}
                """);
        io.tesseraql.yaml.view.ViewSpec spec = io.tesseraql.yaml.view.ViewSpec
                .parse(dir.resolve("page.view.yml"));
        io.tesseraql.yaml.scaffold.ScaffoldedFile ejected = io.tesseraql.yaml.view.ViewEjector
                .eject(dir, dir, "page.view.yml", spec, List.of(), "page.html");
        Files.writeString(dir.resolve("page.html"), ejected.content());
        String html = Templates.render(dir, "page.html", Map.of(
                "sql", Map.of("rows", List.of(Map.of("id", 7, "name", "Bolt")))),
                java.util.Locale.ENGLISH);
        assertThat(html).contains("href=\"/items/7\"").contains(">Bolt</a>");
        assertThat(html).contains("hc-datagrid__table");
    }
}
