package io.tesseraql.compiler.binding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import tools.jackson.databind.ObjectMapper;

/**
 * Declarative views rendered through the {@code tql/view/*} pattern fragments (roadmap Phase 39,
 * docs/declarative-views.md): the list datagrid over live rows, the form derived from the action
 * route's {@code input:} block, and the customization-ladder L2 pattern override.
 */
class HtmlResponseRendererViewTest {

    /** The compiler's test classpath carries the csv codec (tesseraql-operations, test scope). */
    private static final io.tesseraql.core.files.FileCodecs CODECS = io.tesseraql.core.files.FileCodecs
            .discover(HtmlResponseRendererViewTest.class.getClassLoader());

    private static final ObjectMapper MAPPER = io.tesseraql.yaml.JsonMappers.constrained();

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

    /** The same shape with a declared lock (docs/edit-conflict.md decision 3). */
    private static RouteDefinition lockedActionRoute() {
        return MAPPER.convertValue(Map.of(
                "id", "items.update",
                "kind", "route",
                "recipe", "command-json",
                "lock", "version",
                "input", Map.of("name", Map.of("type", "string", "required", true))),
                RouteDefinition.class);
    }

    private static HtmlResponseRenderer renderer(Path dir, String viewYaml) throws Exception {
        return renderer(dir, viewYaml, null);
    }

    private static HtmlResponseRenderer renderer(Path dir, String viewYaml,
            RouteDefinition route) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), viewYaml);
        ViewBinding binding = ViewBinding.of(dir, "page", route,
                path -> switch (path) {
                    case "/items/create" -> actionRoute();
                    case "/items/update" -> lockedActionRoute();
                    default -> null;
                },
                id -> dir.resolve("page.view.yml"), CODECS);
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
                id -> dir.resolve("page.view.yml"), CODECS);
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
        ViewBinding binding = ViewBinding.of(dir, "page", route, path -> null, registry(dir),
                CODECS);
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
        ViewBinding binding = ViewBinding.of(dir, "page", route, path -> null, registry(dir),
                CODECS);
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en",
                binding);
        // The child's source: audit remaps onto the embedded document's own source (sql).
        String html = render(renderer, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 5, "name", "Bolt"))),
                "audit", Map.of("rows", List.of(Map.of("event", "created")))));
        assertThat(html).contains("History").contains(">created<");
    }

    /**
     * The document's own {@code source:} is judged like a panel's (docs/audit-low-leads.md
     * slice 21, unfiled 22): a typo used to bind, read an empty result and render an empty page.
     */
    @Test
    void aViewsOwnSourceTheRouteDoesNotDeclareFailsTheBuild(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                source: typo
                """);
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "items", "kind", "route", "recipe", "query-html",
                "sources", Map.of("main", Map.of("sql", Map.of("file", "items.sql")))),
                RouteDefinition.class);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", route, path -> null, registry(dir),
                CODECS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3308")
                .hasMessageContaining("view page: source typo");
    }

    /**
     * An embedded document's own {@code source:} is held to the host route unless the host
     * entry overrides it — then the override is the name that must be declared, and the
     * embedded document's own may be anything its hosts remap.
     */
    @Test
    void anEmbeddedViewsOwnSourceIsJudgedAgainstTheHostUnlessOverridden(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("inner.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                source: inner
                """);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: inner }
                """);
        RouteDefinition route = MAPPER.convertValue(Map.of(
                "id", "board", "kind", "route", "recipe", "query-html",
                "sources", Map.of("recent", Map.of("sql", Map.of("file", "recent.sql")))),
                RouteDefinition.class);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", route, path -> null, registry(dir),
                CODECS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("view inner: source inner");

        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: inner, source: recent }
                """);
        assertThat(ViewBinding.of(dir, "page", route, path -> null, registry(dir), CODECS))
                .isNotNull();
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: dashboard
                panels:
                  - { type: view, view: inner, source: ghost }
                """);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", route, path -> null, registry(dir),
                CODECS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("view page: panel source ghost");
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
        assertThatThrownBy(
                () -> ViewBinding.of(dir, "page", null, path -> null, registry(dir), CODECS))
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
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null, registry(dir),
                CODECS);
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
        ViewBinding binding = ViewBinding.of(dir, "page", null, path -> null, registry(dir),
                CODECS);
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
                id -> dir.resolve("page.view.yml"), CODECS);
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
                id -> dir.resolve("page.view.yml"), CODECS);
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
                id -> dir.resolve("page.view.yml"), CODECS))
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
        ViewBinding bound = ViewBinding.of(dir, "recent", null, path -> null, registry(dir),
                CODECS);
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
    void aFormRendersItsFieldsInTheDeclaredOrder(@TempDir Path dir) throws Exception {
        // The fixture is parsed from text, never built with Map.of: a Map.of literal is salted
        // before the route sees it, so a test written that way never crosses the boundary under
        // test. These six names are the scaffold-demo create form's, chosen by enumeration —
        // twelve reachable iteration orders and the declared one is not among them, so this
        // assertion failed on every boot before the fix (docs/deterministic-output.md).
        RouteDefinition action = MAPPER.readValue("""
                {"id": "items.create", "kind": "route", "recipe": "command-json",
                 "input": {"name": {"type": "string", "required": true},
                           "quantity": {"type": "integer"},
                           "unit_price": {"type": "decimal"},
                           "due_date": {"type": "date"},
                           "active": {"type": "boolean"},
                           "note": {"type": "string"}}}
                """, RouteDefinition.class);
        Files.writeString(dir.resolve("page.view.yml"), """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/ordered
                """);
        ViewBinding binding = ViewBinding.of(dir, "page", null,
                path -> "/items/ordered".equals(path) ? action : null,
                id -> dir.resolve("page.view.yml"), CODECS);
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en", binding);

        assertThat(render(renderer, Map.of())).containsSubsequence("field-name", "field-quantity",
                "field-unit_price", "field-due_date", "field-active", "field-note");
        // …and the map the form derives from, so a later regression is diagnosed at the layer it
        // happens in rather than only at the rendered page.
        assertThat(action.input().keySet()).containsExactly("name", "quantity", "unit_price",
                "due_date", "active", "note");
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
        // The dialog names itself (docs/audit-low-leads.md slice 23, F100): the kit's contract
        // accepts a title before the first focusable, and the sibling dialogs all say it
        // explicitly; an unnamed dialog role is what the accessibility tree reported.
        assertThat(html).contains("aria-labelledby=\"page-filters-title\"")
                .contains("id=\"page-filters-title\"");
        // The enum input renders a select whose first option is the empty "any" choice.
        assertThat(html).contains("hc-select")
                .containsSubsequence("name=\"status\"", "<option value=\"\">",
                        "<option value=\"OPEN\"");
        assertThat(html).contains("name=\"status\" value=\"OPEN\"");
    }

    /**
     * Every element between the list page and its datagrid is a link of the fill chain
     * (docs/list-surface.md decision 1; docs/audit-low-leads.md slice 23, unfiled 25). The chain
     * is a set of descendant rules in {@code tesseraql.css}, so a wrapper inserted without a
     * class silently breaks it: the bulk-action {@code <form>} did exactly that — the region grew
     * to its rows, the page scrolled and the chrome scrolled away on every desktop list over a
     * viewport, with every markup test green. No browser harness exists here, so the guard is on
     * the markup path: the classes the CSS chains, on every ancestor down to the grid.
     */
    @Test
    void everyWrapperBetweenTheListPageAndTheDatagridIsALinkOfTheFillChain(@TempDir Path dir)
            throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                filters: [status]
                """, actionRoute());
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 1, "name", "Bolt")))));
        exchange.request().uri("/items");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);

        List<String> chain = List.of("tql-list-page", "tql-list-page__form",
                "tql-list-page__region", "tql-list-page__grid");
        List<String> ancestors = ancestorClassesOf(html, "hc-datagrid", "tql-list-page");
        assertThat(ancestors).as("the class attributes on the path from .tql-list-page to the grid")
                .isNotEmpty()
                .allSatisfy(classes -> assertThat(classes.split("\\s+"))
                        .as("a wrapper on the fill chain: class=\"%s\"", classes)
                        .anyMatch(chain::contains));
        // That the CSS chains exactly these classes is HypermediaComponentsManifestTest's
        // assertion (tesseraql-runtime owns the stylesheet).
    }

    /**
     * The {@code class} attribute of every open element enclosing the first element carrying
     * {@code target}, from {@code root} (exclusive) down, by a tag walk over the rendered
     * markup — the templates emit well-formed HTML, and the void elements need no close.
     */
    private static List<String> ancestorClassesOf(String html, String target, String root) {
        java.util.Set<String> voids = java.util.Set.of("input", "br", "hr", "img", "meta",
                "link", "col", "wbr", "source", "area", "base", "embed", "param", "track");
        java.util.regex.Matcher tags = java.util.regex.Pattern
                .compile("<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*)>").matcher(html);
        java.util.Deque<String[]> open = new java.util.ArrayDeque<>();
        while (tags.find()) {
            String name = tags.group(2).toLowerCase(java.util.Locale.ROOT);
            if (!tags.group(1).isEmpty()) {
                while (!open.isEmpty() && !open.pop()[0].equals(name)) {
                    // an unclosed inline element above the closer: popped with it
                }
                continue;
            }
            if (voids.contains(name) || tags.group(3).endsWith("/")) {
                continue;
            }
            java.util.regex.Matcher classAttr = java.util.regex.Pattern
                    .compile("\\sclass=\"([^\"]*)\"").matcher(tags.group(3));
            String classes = classAttr.find() ? classAttr.group(1) : "";
            if (java.util.Arrays.asList(classes.split("\\s+")).contains(target)) {
                List<String> path = new java.util.ArrayList<>();
                for (String[] element : open) {
                    if (java.util.Arrays.asList(element[1].split("\\s+")).contains(root)) {
                        break;
                    }
                    path.add(element[1]);
                }
                return path;
            }
            open.push(new String[]{name, classes});
        }
        return List.of();
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

    /**
     * Every list state stays a bookmarkable address (docs/list-surface.md decision 1, kept
     * for the pager alone until docs/list-export.md's filed quirk): the search box replaces
     * the URL with each swap, a sort header pushes it, and the chrome outside the swapped
     * region that carries the state — the dialog's hidden sort/dir/search and the condition
     * chips — is named so the same swaps refresh it out of band through the section's
     * inherited {@code hx-select-oob}. A list with no filters has nothing outside the region
     * to refresh and carries no selector.
     */
    @Test
    void inPlaceSearchAndSortKeepTheUrlAndTheDialogsCarriedState(@TempDir Path dir)
            throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                search: q
                filters:
                  - status
                columns:
                  - { name: id, sortable: true }
                """, actionRoute());
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, Map.of(
                "main", Map.of("rows", List.of(Map.of("id", 1))),
                "params", Map.of("status", "OPEN", "q", "bolt", "sort", "-id")));
        exchange.request().uri("/items?status=OPEN&q=bolt&sort=-id");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);
        assertThat(html)
                .contains("hx-select-oob=\"#page-filters-state,#page-filterbar\"")
                .containsSubsequence("id=\"page-search\"", "hx-replace-url=\"true\"")
                .containsSubsequence("hc-datagrid__headcell", "sort=", "hx-push-url=\"true\"")
                .contains("id=\"page-filterbar\"")
                // The dialog's carried state: what a typed search and a sort click must keep.
                .containsSubsequence("id=\"page-filters-state\"",
                        "name=\"sort\" value=\"-id\"", "name=\"q\" value=\"bolt\"",
                        "hc-dialog__header");
        // The chips' remove links carry the search and sort of this render — the reason the
        // bar is refreshed with every swap.
        assertThat(html).contains("hc-filterbar__remove")
                .contains("href=\"/items?sort=-id&amp;q=bolt\"");

        HtmlResponseRenderer plain = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                search: q
                """);
        // The attribute, not the template's own comments, which name it.
        assertThat(render(plain, Map.of("main", Map.of("rows", List.of()))))
                .doesNotContain("hx-select-oob=\"").contains("hx-replace-url=\"true\"");
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
        // The declared bound made visible (docs/hypermedia-ui.md "Bounded text fields"): the
        // kit's count on the string input, its output pre-rendered as used / max; the integer
        // input carries none, a number having no string bound.
        assertThat(html).contains("data-hc-count")
                .contains("aria-describedby=\"field-name-count\"")
                .contains("id=\"field-name-count\" for=\"field-name\"")
                .contains(">0 / 200</output>")
                .doesNotContain("field-quantity-count");
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
        // A textarea grows with its content and, bounded by the route's maxLength, counts.
        assertThat(html).contains("data-autosize").contains(">0 / 200</output>");
        // Unselected inputs are not rendered.
        assertThat(html).doesNotContain("name=\"quantity\"");
    }

    @Test
    void aLockedFormCarriesTheRecordLockAsAFrameworkOwnedHiddenField(@TempDir Path dir)
            throws Exception {
        String html = render(lockedRenderer(dir), rowContext(Map.of(
                "id", 7, "name", "Bolt", "version", 3)));

        assertThat(html).contains("name=\"_lock\"").contains("value=\"3\"");
    }

    @Test
    void anUnlockedFormCarriesNoLockField(@TempDir Path dir) throws Exception {
        HtmlResponseRenderer renderer = renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/create
                """);

        assertThat(render(renderer, Map.of())).doesNotContain("name=\"_lock\"");
    }

    @Test
    void aRowMissingTheLockColumnRefusesTheRender(@TempDir Path dir) throws Exception {
        // The whole point of decision 3: a hidden field guarded on the value would vanish here
        // and leave the save silently unlocked.
        HtmlResponseRenderer renderer = lockedRenderer(dir);
        Map<String, Object> context = rowContext(Map.of("id", 7, "name", "Bolt"));

        assertThatThrownBy(() -> render(renderer, context))
                .hasMessageContaining("TQL-VIEW-3330")
                .hasMessageContaining("'version'")
                .hasMessageContaining("is not in the rendered row");
    }

    @Test
    void aNullLockValueRefusesTheRenderToo(@TempDir Path dir) throws Exception {
        // An equality predicate on null matches no row, so the form would be unsaveable rather
        // than unlocked — worse than either.
        Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", 7);
        row.put("name", "Bolt");
        row.put("version", null);
        HtmlResponseRenderer renderer = lockedRenderer(dir);
        Map<String, Object> context = rowContext(row);

        assertThatThrownBy(() -> render(renderer, context))
                .hasMessageContaining("TQL-VIEW-3330")
                .hasMessageContaining("is null");
    }

    @Test
    void aFoldedResultSetLabelStillResolvesTheLock(@TempDir Path dir) throws Exception {
        // Some dialects fold labels to upper case; the lookup field resolves its columns the
        // same way.
        String html = render(lockedRenderer(dir), rowContext(Map.of(
                "ID", 7, "NAME", "Bolt", "VERSION", 3)));

        assertThat(html).contains("name=\"_lock\"").contains("value=\"3\"");
    }

    @Test
    void aCreateFormOnALockedRouteRendersNoLockAndDoesNotRefuse(@TempDir Path dir)
            throws Exception {
        // No record, so no lock to send. The submit answers TQL-FIELD-2011, which is the loud
        // failure decision 3 asks for rather than a silent one.
        assertThat(render(lockedRenderer(dir), Map.of())).doesNotContain("name=\"_lock\"");
    }

    private static HtmlResponseRenderer lockedRenderer(Path dir) throws Exception {
        return renderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: form
                action: /items/update
                """);
    }

    private static Map<String, Object> rowContext(Map<String, Object> row) {
        return Map.of("main", Map.of("rows", List.of(row)));
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
                id -> dir.resolve("page.view.yml"), CODECS);
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
                id -> dir.resolve("page.view.yml"), CODECS))
                .isInstanceOf(TqlException.class).hasMessageContaining("matches no POST route");
    }

    // The export controls (docs/list-export.md decisions 1-3, 7, 8).

    /**
     * The question's inputs in declaration order — a YAML mapping loads as an ordered map, and
     * the controls' query follows that order, so the fixture must keep it too.
     */
    private static Map<String, Object> questionInputs() {
        Map<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("q", Map.of("type", "string"));
        inputs.put("status", Map.of("type", "string", "enum", List.of("OPEN", "CLOSED")));
        inputs.put("sort", Map.of("type", "string"));
        inputs.put("dir", Map.of("type", "string", "enum", List.of("asc", "desc")));
        return inputs;
    }

    /** The list route whose question the controls carry: a search, a filter, a sort. */
    private static RouteDefinition listRoute(Map<String, Object> pagination) {
        Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("id", "items.page");
        definition.put("kind", "route");
        definition.put("recipe", "query-html");
        definition.put("input", questionInputs());
        if (pagination != null) {
            definition.put("pagination", pagination);
        }
        return MAPPER.convertValue(definition, RouteDefinition.class);
    }

    /** An export route at {@code path} declaring the list's inputs, with the given security. */
    private static io.tesseraql.yaml.manifest.RouteFile exportRoute(Path dir, String method,
            String path, String recipe, Map<String, Object> security) {
        Map<String, Object> definition = new java.util.LinkedHashMap<>();
        definition.put("id", "items.export");
        definition.put("kind", "route");
        definition.put("recipe", recipe);
        definition.put("input", questionInputs());
        definition.put("export", Map.of("format", "csv"));
        if (security != null) {
            definition.put("security", security);
        }
        return new io.tesseraql.yaml.manifest.RouteFile(method, path,
                dir.resolve("export.yml"), MAPPER.convertValue(definition, RouteDefinition.class));
    }

    private static HtmlResponseRenderer exportRenderer(Path dir, String viewYaml,
            RouteDefinition route, List<io.tesseraql.yaml.manifest.RouteFile> routes)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("page.view.yml"), viewYaml);
        ViewBinding binding = ViewBinding.of(dir, "page", route, path -> null,
                id -> dir.resolve("page.view.yml"), CODECS,
                path -> routes.stream().filter(r -> r.urlPath().equals(path)).toList());
        return new HtmlResponseRenderer(new HtmlResponse(200, null, "page", null, null,
                Map.of(), Map.of(), Map.of(), null), dir, dir, "en", binding);
    }

    private static final String EXPORTING_LIST = """
            version: tesseraql/v1
            kind: view
            recipe: list
            search: q
            filters: [status]
            exports: [/items/export]
            """;

    private static String renderList(HtmlResponseRenderer renderer, Map<String, Object> extra)
            throws Exception {
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("main", Map.of("rows", List.of(Map.of("id", 1), Map.of("id", 2))));
        context.put("params", Map.of("q", "vpn", "status", "OPEN", "sort", "-created_at"));
        context.putAll(extra);
        Exchange exchange = new Exchange(Beans.NONE);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        exchange.request().uri("/items?q=vpn&status=OPEN&sort=-created_at&size=20&page=2");
        renderer.process(exchange);
        return exchange.getBody(String.class);
    }

    /**
     * The job region remembers (docs/job-inbox.md decision 8): with a transfer service, an
     * application name and a principal, the caller's pending exports of the file-export target
     * render as cards at page render, their URLs prefixed with what the request binder
     * published as {@code request.basePath}; without a principal the region renders empty,
     * because nobody's exports are nobody's to show.
     */
    @Test
    void theJobRegionRendersTheCallersPendingExportsAndNothingForAnonymous(@TempDir Path dir)
            throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("page.view.yml"), EXPORTING_LIST);
        List<io.tesseraql.yaml.manifest.RouteFile> routes = List.of(
                exportRoute(dir, "POST", "/items/export", "file-export", null));
        ViewBinding binding = ViewBinding.of(dir, "page", listRoute(null), path -> null,
                id -> dir.resolve("page.view.yml"), CODECS,
                path -> routes.stream().filter(r -> r.urlPath().equals(path)).toList(),
                "demo-app");
        HtmlResponseRenderer renderer = new HtmlResponseRenderer(new HtmlResponse(200, null,
                "page", null, null, Map.of(), Map.of(), Map.of(), null), dir, dir, "en", binding);
        io.tesseraql.core.files.FileTransferService.TransferStatus pending = new io.tesseraql.core.files.FileTransferService.TransferStatus(
                "t-1", "items.export", "demo-app", "EXPORT", "COMPLETED", 2, null, List.of(),
                "items.csv", false, null, null, false, null);
        List<List<Object>> asked = new java.util.ArrayList<>();
        io.tesseraql.core.files.FileTransferService transfers = (io.tesseraql.core.files.FileTransferService) java.lang.reflect.Proxy
                .newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{io.tesseraql.core.files.FileTransferService.class},
                        (proxy, method, args) -> {
                            if ("pending".equals(method.getName())) {
                                asked.add(java.util.Arrays.asList(args));
                                return List.of(pending);
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
        io.tesseraql.pipeline.Beans beans = new io.tesseraql.pipeline.Beans() {
            @Override
            public <T> T lookup(String name, Class<T> type) {
                return TesseraqlProperties.FILE_TRANSFER_BEAN.equals(name)
                        ? type.cast(transfers)
                        : null;
            }
        };

        // Signed in: the region holds the card, its URLs the route's subtree under the prefix.
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("main", Map.of("rows", List.of(Map.of("id", 1))));
        context.put("params", Map.of("q", "vpn", "status", "OPEN", "sort", "-created_at"));
        context.put("principal", new io.tesseraql.security.Principal("u-42", "u-42", "U",
                null, List.of(), List.of(), List.of(), Map.of()));
        context.put("request", Map.of("basePath", "/erp"));
        Exchange exchange = new Exchange(beans);
        exchange.setProperty(TesseraqlProperties.CONTEXT, context);
        exchange.request().uri("/items");
        renderer.process(exchange);
        String html = exchange.getBody(String.class);
        assertThat(html)
                .contains("id=\"page-export-job\"")
                .contains("id=\"tql-job-t-1\"")
                .contains("hx-get=\"/erp/items/export/t-1\"")
                .contains("href=\"/erp/items/export/t-1/file\"")
                .contains("hx-swap=\"afterbegin\"");
        // Asked for this application, this route, this subject, at most five.
        assertThat(asked).hasSize(1);
        assertThat(asked.get(0)).containsExactly("demo-app", "items.export", "u-42", null, 5);

        // Anonymous: nothing asked, nothing rendered, the region still there for a kick-off.
        asked.clear();
        Map<String, Object> anonymous = new java.util.LinkedHashMap<>(context);
        anonymous.remove("principal");
        Exchange open = new Exchange(beans);
        open.setProperty(TesseraqlProperties.CONTEXT, anonymous);
        open.request().uri("/items");
        renderer.process(open);
        assertThat(open.getBody(String.class))
                .contains("id=\"page-export-job\"")
                .doesNotContain("tql-job-");
        assertThat(asked).isEmpty();
    }

    @Test
    void aQueryExportRendersALinkCarryingTheListsQuestion(@TempDir Path dir) throws Exception {
        // Decision 2: the search, the filter and the sort travel as the route's query; the page
        // window does not. Decision 3: the control sits in the strip, beside the count.
        HtmlResponseRenderer renderer = exportRenderer(dir, EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export", null)));
        String html = renderList(renderer, Map.of(
                "page", Map.of("number", 2, "size", 20, "totalRows", 56)));
        assertThat(html)
                .contains("href=\"/items/export?q=vpn&amp;status=OPEN&amp;sort=-created_at\"");
        assertThat(html).doesNotContain("size=20").doesNotContain("page=2\"");
        assertThat(html).contains(">Export 56 rows</a>");
        // The count and the control share the strip, inside the swapped region (two rows on
        // page 2 of size 20 read as 21–22 of 56).
        assertThat(html).containsSubsequence("id=\"page-table\"", "21–22 of 56",
                "Export 56 rows", "hc-pagination");
        assertThat(html).doesNotContain("id=\"page-export\"");
    }

    @Test
    void aFileExportRendersTheKickoffFormAndItsButton(@TempDir Path dir) throws Exception {
        // Decision 3: the button belongs, by form=, to a small form that precedes the grid form
        // — a form cannot nest — and carries the same question in its formaction.
        HtmlResponseRenderer renderer = exportRenderer(dir, EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "POST", "/items/export", "file-export", null)));
        String html = renderList(renderer, Map.of());
        assertThat(html).contains("<form id=\"page-export\" method=\"post\">")
                .contains("name=\"_idempotency\"");
        assertThat(html).contains("form=\"page-export\"")
                .contains("formaction=\"/items/export?q=vpn&amp;status=OPEN&amp;sort=-created_at\"")
                .contains(">Export</button>");
        assertThat(html.indexOf("id=\"page-export\""))
                .isLessThan(html.indexOf("class=\"tql-list-page__form\""));
        // The htmx face (decisions 4-5): the same URL, the kick-off form's two fields only, the
        // answer into the job region — which sits before the kick-off form and the grid form,
        // outside the swapped table region, and renders empty.
        assertThat(html)
                .contains("hx-post=\"/items/export?q=vpn&amp;status=OPEN&amp;sort=-created_at\"")
                .contains("hx-include=\"#page-export\"")
                .contains("hx-params=\"_csrf,_idempotency\"")
                .contains("hx-target=\"#page-export-job\"").contains("hx-disabled-elt=\"this\"")
                .contains("<div id=\"page-export-job\"></div>");
        assertThat(html.indexOf("id=\"page-export-job\""))
                .isLessThan(html.indexOf("id=\"page-export\" method"))
                .isLessThan(html.indexOf("id=\"page-table\""));
    }

    @Test
    void aQueryExportAloneRendersNoJobRegionAndNoKickoffForm(@TempDir Path dir)
            throws Exception {
        HtmlResponseRenderer renderer = exportRenderer(dir, EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export", null)));
        String html = renderList(renderer, Map.of());
        assertThat(html).doesNotContain("page-export-job").doesNotContain("id=\"page-export\"")
                .doesNotContain("hx-post=\"/items/export");
    }

    @Test
    void theExportLabelHedgesAsTheStatusLineDoes(@TempDir Path dir) throws Exception {
        // Decision 3: an exact total names itself; a truncated one says "all matching"; no
        // total says nothing. An authored label renders as written.
        HtmlResponseRenderer renderer = exportRenderer(dir, EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export", null)));
        assertThat(renderList(renderer, Map.of("page", Map.of("number", 1, "size", 20,
                "totalRows", 56)))).contains(">Export 56 rows</a>");
        assertThat(renderList(renderer, Map.of("page", Map.of("number", 1, "size", 20),
                "main", Map.of("rows", List.of(Map.of("id", 1)), "truncated", true))))
                .contains(">Export all matching rows</a>")
                .contains("or export the full set.");
        assertThat(renderList(renderer, Map.of())).contains(">Export</a>");
        HtmlResponseRenderer labelled = exportRenderer(dir.resolve("labelled"), """
                version: tesseraql/v1
                kind: view
                recipe: list
                exports:
                  - { action: /items/export, label: Spreadsheet }
                """, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export", null)));
        assertThat(renderList(labelled, Map.of("page", Map.of("number", 1, "size", 20,
                "totalRows", 56)))).contains(">Spreadsheet</a>");
    }

    @Test
    void theOverCapRejectBlockCarriesTheExportControl(@TempDir Path dir) throws Exception {
        // Decision 8: over the snapshot cap the page shows no rows and no pager, so the reject
        // block's actions part carries the control its copy names.
        HtmlResponseRenderer renderer = exportRenderer(dir, """
                version: tesseraql/v1
                kind: view
                recipe: list
                key: id
                search: q
                exports: [/items/export]
                """, listRoute(Map.of("strategy", "snapshot", "size", 20, "cap", 500)),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export", null)));
        String html = renderList(renderer, Map.of("page", Map.of("hasNext", true)));
        assertThat(html).contains("hc-empty__actions")
                .contains("or export the full set.")
                .containsSubsequence("data-hc-result-cap", "href=\"/items/export?q=vpn");
        assertThat(html).contains(">Export</a>").doesNotContain("Export 5");
    }

    @Test
    void anExportThePrincipalMayNotUseIsNotRendered(@TempDir Path dir) throws Exception {
        // Decision 7: a policy no engine permits (Beans.NONE has none) hides the control, and
        // a route wanting a principal hides it from an anonymous request; a public one shows.
        HtmlResponseRenderer gated = exportRenderer(dir, EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export",
                        Map.of("auth", "browser", "policy", "items.export"))));
        assertThat(renderList(gated, Map.of())).doesNotContain("/items/export");
        HtmlResponseRenderer authenticated = exportRenderer(dir.resolve("auth"),
                EXPORTING_LIST, listRoute(null),
                List.of(exportRoute(dir, "GET", "/items/export", "query-export",
                        Map.of("auth", "browser"))));
        assertThat(renderList(authenticated, Map.of())).doesNotContain("/items/export");
        assertThat(renderList(authenticated, Map.of("principal", Map.of("subject", "u1"))))
                .contains("/items/export?q=vpn");
        HtmlResponseRenderer open = exportRenderer(dir.resolve("open"), EXPORTING_LIST,
                listRoute(null), List.of(exportRoute(dir, "GET", "/items/export",
                        "query-export", Map.of("auth", "public"))));
        assertThat(renderList(open, Map.of())).contains("/items/export?q=vpn");
    }

    @Test
    void anExportNamingNoExportRouteFailsTheBind(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.view.yml"), EXPORTING_LIST);
        assertThatThrownBy(() -> ViewBinding.of(dir, "page", listRoute(null), path -> null,
                id -> dir.resolve("page.view.yml"), CODECS))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-VIEW-3331")
                .hasMessageContaining("export /items/export targets no query-export GET route");
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
                id -> dir.resolve("page.view.yml"), CODECS))
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
                id -> dir.resolve("page.view.yml"), CODECS))
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
                id -> dir.resolve("page.view.yml"), CODECS))
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
