package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Beans;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.yaml.model.ResponseSpec.HtmlResponse;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.template.Templates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
        ViewBinding binding = ViewBinding.of(dir, "page", route,
                path -> "/items/create".equals(path) ? actionRoute() : null,
                id -> dir.resolve("page.view.yml"));
        return new HtmlResponseRenderer(new HtmlResponse(200, null, "page", null, null,
                Map.of(), Map.of(), Map.of(), null), dir, dir, "en", binding);
    }

    private static String render(HtmlResponseRenderer renderer, Map<String, Object> context)
            throws Exception {
        return exchangeFor(renderer, context, Map.of()).getBody(String.class);
    }

    private static Exchange exchangeFor(HtmlResponseRenderer renderer,
            Map<String, Object> context, Map<String, String> requestHeaders) throws Exception {
        Exchange exchange = new Exchange(
                Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        requestHeaders.forEach((name, value) -> exchange.request().header(name, value));
        renderer.process(exchange);
        return exchange;
    }

    // Shell negotiation (docs/view-composition.md wave 2a): one URL, both shapes.

    private static HtmlResponseRenderer shellRenderer(Path dir, String shell) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\ntitle: Items\n");
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml"));
        return new HtmlResponseRenderer(new HtmlResponse(200, null, "page", shell, null,
                Map.of(), Map.of(), Map.of(), null), dir, dir, "en", binding);
    }

    @Test
    void anHxRequestGetsTheBareRegionWithVaryOnBoth(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = shellRenderer(dir, null);
        Exchange partial = exchangeFor(renderer, Map.of(), Map.of("HX-Request", "true"));
        String region = partial.getBody(String.class);
        assertThat(region).doesNotContain("<html").contains("hc-datagrid")
                .startsWith("<div id=\"page-content\"");
        assertThat(partial.response().header("Vary"))
                .contains("HX-Request");

        Exchange direct = exchangeFor(renderer, Map.of(), Map.of());
        assertThat(direct.getBody(String.class)).contains("<html");
        assertThat(direct.response().header("Vary"))
                .contains("HX-Request");
    }

    @Test
    void boostedAndHistoryRestoreRequestsGetTheFullPage(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = shellRenderer(dir, null);
        assertThat(exchangeFor(renderer, Map.of(),
                Map.of("HX-Request", "true", "HX-Boosted", "true"))
                .getBody(String.class)).contains("<html");
        assertThat(exchangeFor(renderer, Map.of(),
                Map.of("HX-Request", "true", "HX-History-Restore-Request", "true"))
                .getBody(String.class)).contains("<html");
    }

    @Test
    void shellAlwaysAndNeverOverrideTheNegotiation(@TempDir Path dir) throws Exception {
        Exchange always = exchangeFor(shellRenderer(dir, "always"), Map.of(),
                Map.of("HX-Request", "true"));
        assertThat(always.getBody(String.class)).contains("<html");
        assertThat(always.response().header("Vary")).isNull();

        Exchange never = exchangeFor(shellRenderer(dir, "never"), Map.of(), Map.of());
        assertThat(never.getBody(String.class))
                .doesNotContain("<html").startsWith("<div id=\"page-content\"");
    }

    @Test
    void anInvalidShellValueFailsTheBuild(@TempDir Path dir) throws Exception {
        assertThatThrownBy(() -> shellRenderer(dir, "sometimes"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3317");
    }

    // Embedding (docs/view-composition.md wave 2b/2c): views embed views; the route stays
    // the sole data owner.

    private static java.util.function.Function<String, Path> registry(Path dir) {
        return id -> {
            Path file = dir.resolve(id + ".view.yml");
            return Files.isRegularFile(file) ? file : null;
        };
    }

    @Test
    void aDashboardEmbedsAnotherViewAsAPanel(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("recent.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Recent items
                source: recent
                """);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                title: Board
                panels:
                  - { type: stat, source: main, column: total, title: Total }
                  - { type: view, view: recent }
                """);
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "board", "kind", "route", "recipe", "query-html",
                "sources", Map.of("recent", Map.of("sql", Map.of("file", "recent.sql")))),
                RouteDefinition.class);
        ViewBinding binding = ViewBinding.of(dir, "page", route, path -> null, registry(dir));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en",
                binding);
        String html = render(renderer, Map.of(
                "main", Map.of("rows", List.of(Map.of("total", 9))),
                "recent", Map.of("rows", List.of(Map.of("id", 1, "name", "Bolt")))));
        assertThat(html).contains(">9</strong>");
        // The embedded list renders through its own pattern, card and datagrid included.
        assertThat(html).contains("Recent items").contains("hc-datagrid__table")
                .contains(">Bolt<");
    }

    @Test
    void aDetailChildEmbedsAViewWithASourceOverride(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("history.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: History
                """);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: detail
                title: Item
                children:
                  - { view: history, source: audit }
                """);
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "item", "kind", "route", "recipe", "query-html",
                "sources", Map.of("audit", Map.of("sql", Map.of("file", "audit.sql")))),
                RouteDefinition.class);
        ViewBinding binding = ViewBinding.of(dir, "page", route, path -> null, registry(dir));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en",
                binding);
        // The child's source: audit remaps onto the embedded document's own source (sql).
        String html = render(renderer, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 5, "name", "Bolt"))),
                "audit", Map.of("rows", List.of(Map.of("event", "created")))));
        assertThat(html).contains("History").contains(">created<");
    }

    @Test
    void embeddingDepthIsOne(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("inner.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.writeString(dir.resolve("middle.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: inner }
                """);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: middle }
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null, registry(dir)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3318");
    }

    @Test
    void routeModelEntriesMergeAlongsideV(@TempDir Path dir) throws Exception {
        // model: entries used to be discarded on view: routes (docs/view-composition.md 2b);
        // a per-view template retarget proves both merge sides render.
        Files.writeString(dir.resolve("banner.html"), "<p th:text=\"${banner}\"></p>"
                + "<th:block th:insert=\"~{tql/view/list :: view(${v})}\"/>");
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Items
                template: banner.html
                """);
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null, registry(dir));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of("banner", "notice.text"), Map.of(), Map.of(), null),
                dir, dir, "en", binding);
        String html = render(renderer, Map.of(
                "notice", Map.of("text", "maintenance tonight"),
                "main", Map.of("rows", List.of(Map.of("id", 1)))));
        assertThat(html).contains("maintenance tonight").contains("hc-datagrid__table");
    }

    @Test
    void modelDeclaringVIsReserved(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null, registry(dir));
        assertThatThrownBy(() -> new HtmlResponseRenderer(new HtmlResponse(200, null, "page",
                null, null, Map.of("v", "main.rows"), Map.of(), Map.of(), null), dir, dir, "en",
                binding))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3319");
    }

    // Domain presentation (docs/view-composition.md wave 3a).

    @Test
    void aDomainWidgetHintDrivesTheDerivedWidgetAndTheViewOverrideWins(@TempDir Path dir)
            throws Exception {
        // The action route's input carries widget: textarea (merged in from its domain).
        RouteDefinition action = MAPPER.convertValue(Map.of(
                "id", "items.create", "kind", "route", "recipe", "command-json",
                "input", Map.of(
                        "note", Map.of("type", "string", "widget", "textarea"),
                        "sku", Map.of("type", "string", "widget", "textarea"))),
                RouteDefinition.class);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                fields:
                  - name: note
                  - { name: sku, widget: text }
                """);
        ViewBinding binding = ViewBinding.of(dir, "page", null,
                path -> "/items/create".equals(path) ? action : null,
                id -> dir.resolve("page.view.yml"));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en",
                binding);
        String html = render(renderer, Map.of());
        // note follows the domain hint; sku's per-view override wins over it.
        assertThat(html).contains("<textarea");
        assertThat(html).contains("name=\"sku\"").contains("type=\"text\"");
    }

    @Test
    void aColumnDomainMasksTheRenderedCellExactlyLikeJson(@TempDir Path dir) throws Exception {
        // docs/view-composition.md wave 3b: the same FieldPolicyApplier vocabulary — one row
        // can never render masked in JSON and raw in HTML.
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  cardNumber:
                    type: string
                    mask: last4
                """);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: holder
                  - { name: card, domain: cardNumber }
                """);
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml"));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en",
                binding);
        String html = render(renderer, Map.of("main", Map.of("rows", List.of(
                Map.of("holder", "Sato", "card", "4111111111111111")))));
        String masked = String.valueOf(
                io.tesseraql.core.mask.Masking.apply("last4", "4111111111111111"));
        assertThat(html).contains(">Sato<").contains(masked)
                .doesNotContain("4111111111111111");
    }

    @Test
    void aColumnDomainReferenceMustResolve(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - { name: sku, domain: ghost }
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("unknown domain 'ghost'");
    }

    @Test
    void aTemplateRouteBindsViewModels(@TempDir Path dir) throws Exception {
        // Declarative parts on a hand-owned template (wave 2c): the ladder's round-trip.
        Files.writeString(dir.resolve("overview.html"), "<h1>Overview</h1>"
                + "<th:block th:insert=\"~{tql/view/list :: view(${views['recent']})}\"/>");
        Files.writeString(dir.resolve("recent.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Recent items
                """);
        ViewBinding bound = ViewBinding.of(dir, "recent", null, path -> null, registry(dir));
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200,
                "overview.html", null, null, List.of("recent"), Map.of(), Map.of(), Map.of(),
                null), dir, dir, "en", null, Map.of("recent", bound));
        String html = render(renderer, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 1, "name", "Bolt")))));
        assertThat(html).contains("<h1>Overview</h1>").contains("Recent items")
                .contains(">Bolt<");
    }

    @Test
    void aListViewRendersTheQuerysOwnColumnsAsADatagrid(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Items
                """);
        String html = render(renderer, Map.of("main", Map.of("rows", List.of(
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
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{id}
                """);
        String html = render(renderer, Map.of("main", Map.of("rows", List.of(
                Map.of("id", 7, "name", "Bolt")))));
        assertThat(html).contains("href=\"/items/7\"").contains(">Bolt</a>");
    }

    @Test
    void aListColumnLinkEncodesTheSubstitutedValue(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 3: only the values are encoded, never the template's
        // own separators — a key containing / or ? used to break the href.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: name
                    link: /items/{id}
                """);
        String html = render(renderer, Map.of("main", Map.of("rows", List.of(
                Map.of("id", "a/b?c", "name", "Bolt")))));
        assertThat(html).contains("/items/a%2Fb%3Fc").doesNotContain("/items/a/b");
    }

    @Test
    void aListRendersTheGridFrameWithAnInPlacePager(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 1: the grid page frame — status line and pager live
        // inside the swapped region, page links swap it in place and push the URL.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Tickets
                """);
        Map<String, Object> context = Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 1, "name", "Bolt"))),
                "page", Map.of("number", 2, "size", 1, "hasNext", true, "hasPrev", true,
                        "totalRows", 3, "totalPages", 3));
        String html = render(renderer, context);
        assertThat(html).contains("tql-list-page").contains("hc-datagrid__table")
                .contains(">Bolt<");
        assertThat(html).contains("hx-push-url").contains("hc-pagination");
        // A counted offset page shows its absolute window (tql.view.range).
        assertThat(html).contains("2–2 of 3");
    }

    @Test
    void aDeclaredKeyRendersRowAnchors(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 2: the row's machine identity — base64url("7") = Nw.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                """);
        String html = render(renderer, Map.of("main", Map.of("rows", List.of(
                Map.of("id", 7, "name", "Bolt")))));
        assertThat(html).contains("id=\"row-Nw\"");
    }

    @Test
    void aNullKeyComponentIsARefusalNotASilentSkip(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                """);
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", null);
        row.put("name", "Bolt");
        assertThatThrownBy(() -> render(renderer, Map.of("main", Map.of("rows", List.of(row)))))
                .isInstanceOf(TqlException.class).hasMessageContaining("TQL-VIEW-3322")
                .hasMessageContaining("'id'");
    }

    @Test
    void aPageFrameRowLinkCarriesTheReturnTarget(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 11: the link sends the list's own URL along, with the
        // acting row's fragment, so `location: back` lands back here focused on the row.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                columns:
                  - name: name
                    link: /things/{id}/edit
                """);
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 7, "name", "Bolt"))),
                "page", Map.of("number", 2, "size", 1, "hasNext", false, "hasPrev", true)));
        exchange.request().uri("/things?page=2");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);
        assertThat(html).contains("/things/7/edit?_return=%2Fthings%3Fpage%3D2%23row-Nw");
    }

    @Test
    void aFormEchoesAValidatedReturnTarget(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                """, null);
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of());
        exchange.request().queryParams().put("_return", List.of("/things?page=2#row-Nw"));
        renderer.process(exchange);
        assertThat(exchange.getBody(String.class))
                .contains("name=\"_return\"")
                .contains("value=\"/things?page=2#row-Nw\"");

        // An off-site value is never reflected (docs/list-surface.md decision 11).
        Exchange hostile = new Exchange(Beans.NONE);
        hostile.setProperty(TesseraqlProperties.CONTEXT, Map.of());
        hostile.request().queryParams().put("_return", List.of("https://evil.example/x"));
        renderer.process(hostile);
        assertThat(hostile.getBody(String.class)).doesNotContain("name=\"_return\"");
    }

    @Test
    void declaredFiltersRenderChipsAndTheDialog(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 6: chips for applied conditions (remove = a real URL
        // minus that condition), a dialog of the declared inputs, and the applied filter
        // riding the region as a hidden input for the sort/search swaps.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                filters:
                  - status
                  - { name: quantity, label: Qty }
                """, actionRoute());
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 1))),
                "params", Map.of("status", "OPEN")));
        exchange.request().uri("/items?status=OPEN");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);
        assertThat(html).contains("hc-filterbar__chip").contains(">OPEN<");
        assertThat(html).contains("hc-filterbar__remove").contains("href=\"/items\"");
        assertThat(html).contains("hc-dialog").contains("name=\"quantity\"").contains(">Qty<");
        // The enum input renders a select whose first option is the empty "any" choice.
        assertThat(html).contains("hc-select")
                .containsSubsequence("name=\"status\"", "<option value=\"\">",
                        "<option value=\"OPEN\"");
        assertThat(html).contains("name=\"status\" value=\"OPEN\"");
    }

    @Test
    void presetsRenderAsRealLinksWithTheActiveOneMarked(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 8: no storage — a preset is a link the contract
        // declares; "Modified" marks applied state beyond what the active preset pins.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                filters: [status]
                presets:
                  - name: Open items
                    params: { status: OPEN }
                  - name: Closed items
                    params: { status: CLOSED }
                """, actionRoute());
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of()),
                "params", Map.of("status", "OPEN")));
        exchange.request().uri("/items?status=OPEN");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);
        assertThat(html).contains("href=\"/items?status=OPEN\"")
                .contains("href=\"/items?status=CLOSED\"")
                .contains("aria-current=\"page\"")
                .contains(">Open items</a>");
        assertThat(html).doesNotContain(">Modified<");

        // A search term beyond the active preset marks the view as modified.
        Exchange tweaked = new Exchange(Beans.NONE);
        tweaked.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of()),
                "params", Map.of("status", "OPEN", "q", "bolt")));
        tweaked.request().uri("/items?status=OPEN&q=bolt");
        HtmlResponseRenderer withSearch = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                search: q
                presets:
                  - name: Open items
                    params: { status: OPEN }
                """, actionRoute());
        withSearch.process(tweaked);
        assertThat(tweaked.getBody(String.class)).contains(">Modified<");
    }

    @Test
    void anAppliedMultiSortRendersTheToolbarReadout(@TempDir Path dir) throws Exception {
        // docs/list-surface.md decision 7: the grid page's toolbar says what the sort set is.
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                columns:
                  - name: ship_date
                  - name: order_no
                """);
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of()),
                "params", Map.of("sort", "-ship_date,order_no")));
        exchange.request().uri("/orders?sort=-ship_date%2Corder_no");
        renderer.process(exchange);
        assertThat(exchange.getBody(String.class))
                .contains("Sort (2): Ship date ↓, Order no ↑");
    }

    @Test
    void anEmptyListRendersTheEmptyMessage(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir,
                "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        String html = render(renderer, Map.of());
        assertThat(html).contains("No rows");
    }

    @Test
    void aFormViewDerivesItsFieldsFromTheActionRoutesInputBlock(@TempDir Path dir)
            throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
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
        // The unsaved-changes guard and its badge (docs/hypermedia-ui.md "Unsaved changes"):
        // client-only, so the markup is the whole adoption surface to pin.
        assertThat(html).contains("data-hc-dirty-guard").contains("tql-unsaved-badge");
    }

    @Test
    void fieldsEntriesSelectOrderAndOverride(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
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
                version: tesseraql/v1
                kind: view
                recipe: list
                title: Items
                """);
        String html = render(renderer, Map.of());
        assertThat(html).contains("custom:Items");
        assertThat(html).doesNotContain("hc-datagrid");
    }

    @Test
    void viewAndTemplateTogetherFailTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: list\n");
        Files.writeString(dir.resolve("index.html"), "<p>x</p>");
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml"));
        assertThatThrownBy(() -> new HtmlResponseRenderer(
                new HtmlResponse(200, "index.html", "page.view.yml", null, null, Map.of(), Map.of(),
                        Map.of(),
                        null),
                dir, dir, "en", binding))
                .isInstanceOf(TqlException.class).hasMessageContaining("mutually exclusive");
    }

    @Test
    void aFormActionMatchingNoPostRouteFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"),
                "version: tesseraql/v1\nkind: view\nrecipe: form\naction: /nowhere\n");
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml")))
                .isInstanceOf(TqlException.class).hasMessageContaining("matches no POST route");
    }

    @Test
    void aDetailViewRendersLabelledValuesAndChildren(@TempDir Path dir) throws Exception {
        // The declaring route carries a named query the child composes under the parent row.
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "items.detail",
                "kind", "route",
                "recipe", "query-html",
                "sources", Map.of("orders", Map.of("sql", Map.of("file", "orders.sql")))),
                RouteDefinition.class);
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: detail
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
                "main", Map.of("rows", List.of(Map.of("name", "Bolt", "status", "OPEN"))),
                "orders", Map.of("rows", List.of(Map.of("qty", 3), Map.of("qty", 5)))));
        assertThat(html).contains(">Name</span>").contains(">Bolt</span>");
        assertThat(html).contains(">State</span>").contains(">OPEN</span>");
        assertThat(html).contains(">Orders</h3>").contains(">3</span>").contains(">5</span>");
    }

    @Test
    void aChildSourceTheRouteDoesNotDeclareFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: detail
                children:
                  - source: ghost
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml")))
                .isInstanceOf(TqlException.class).hasMessageContaining("ghost");
    }

    @Test
    void aSlotFillsFromTheAppFragment(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("templates"));
        Files.writeString(dir.resolve("templates/frags.html"),
                "<a th:fragment=\"newLink\" href=\"/items/new\">New item</a>");
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
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
                version: tesseraql/v1
                kind: view
                recipe: list
                slots:
                  sidebar: frags.html::x
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml")))
                .isInstanceOf(TqlException.class).hasMessageContaining("unknown slot");
    }

    @Test
    void aDashboardRendersStatSparklineChartAndTablePanels(@TempDir Path dir) throws Exception {
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "stats",
                "kind", "route",
                "recipe", "query-html",
                "sources", Map.of(
                        "totals", Map.of("sql", Map.of("file", "totals.sql")),
                        "signups", Map.of("sql", Map.of("file", "signups.sql")))),
                RouteDefinition.class);
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
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
        // The chart panel renders the kit's data-hc-chart figure: the source table is the
        // data, the no-JS fallback, and the screen-reader representation; the SVG is drawn
        // client-side by installChart, loaded (with Plot) only because a chart is present.
        assertThat(html).contains("data-hc-chart=\"bar\"")
                .contains("<caption>Signups</caption>")
                .contains("<td>Mon</td><td>2</td>").contains("<td>Tue</td><td>5</td>")
                .contains("plot.umd.min.js").contains("/assets/_tesseraql/charts.js");
        assertThat(html).contains("hc-datagrid__table").contains(">Tue</span>");
    }

    @Test
    void aDashboardPanelSourceTheRouteDoesNotDeclareFailsTheBuild(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - type: stat
                    source: ghost
                    column: c
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", null, path -> null,
                id -> dir.resolve("page.view.yml")))
                .isInstanceOf(TqlException.class).hasMessageContaining("panel source ghost");
    }

    @Test
    void anEjectedListTemplateRendersTheSameRows(@TempDir Path dir) throws Exception {
        // L3: the generated template is real Thymeleaf that renders without the view machinery.
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
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
                "main", Map.of("rows", List.of(Map.of("id", 7, "name", "Bolt")))),
                java.util.Locale.ENGLISH);
        assertThat(html).contains("href=\"/items/7\"").contains(">Bolt</a>");
        assertThat(html).contains("hc-datagrid__table");
    }

    @Test
    void anEjectedDashboardTemplateRendersStatically(@TempDir Path dir) throws Exception {
        // The dashboard eject (docs/pages-and-mail-lints.md follow-ups): every panel kind
        // renders without the view machinery — including the sparkline's OGNL projection.
        Files.writeString(dir.resolve("page.view.yml"), """
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
        io.tesseraql.yaml.view.ViewSpec spec = io.tesseraql.yaml.view.ViewSpec
                .parse(dir.resolve("page.view.yml"));
        io.tesseraql.yaml.scaffold.ScaffoldedFile ejected = io.tesseraql.yaml.view.ViewEjector
                .eject(dir, dir, "page.view.yml", spec, List.of(), "page.html");
        Files.writeString(dir.resolve("page.html"), ejected.content());

        String html = Templates.render(dir, "page.html", Map.of(
                "main", Map.of("rows", List.of(Map.of("user_count", 42))),
                "byStatus", Map.of("rows", List.of(
                        Map.of("status", "ACTIVE", "n", 3),
                        Map.of("status", "DISABLED", "n", 1))),
                "recent", Map.of("rows", List.of(Map.of("name", "sato")))),
                java.util.Locale.ENGLISH);

        assertThat(html).contains(">42</strong>");
        assertThat(html).contains("data-hc-chart=\"bar\"")
                .contains("<td>ACTIVE</td>").contains("<td>3</td>");
        assertThat(html).contains("data-values=\"3,1\"");
        assertThat(html).contains(">sato</td>");
        assertThat(html).contains("plot.umd.min.js");
    }
}
