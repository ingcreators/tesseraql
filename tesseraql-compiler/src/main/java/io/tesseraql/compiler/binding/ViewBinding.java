package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.view.ViewFields;
import io.tesseraql.yaml.view.ViewSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Build-time product of a route's {@code response.html.view} reference (roadmap Phase 39,
 * docs/declarative-views.md): the parsed {@link ViewSpec}, the entry template it renders through
 * (a {@code tql/view/*} pattern, overridable per the customization ladder), and — for a form —
 * the field definitions derived from the {@code action:} route's {@code input:} block, so the
 * rendered HTML constraints are the same declaration {@code InputBinder} enforces server-side.
 *
 * <p>Resolution and derivation fail fast at build; {@link #model} assembles the render-time view
 * model {@code v} the pattern fragments consume — that shape is public API
 * (docs/declarative-views.md).
 */
public final class ViewBinding {

    /** TQL-VIEW-3302: the view reference does not resolve (or clashes with template:). */
    static final TqlErrorCode UNRESOLVED_VIEW = new TqlErrorCode(TqlDomain.VIEW, 3302);
    /** TQL-VIEW-3303: a form's action names no POST route (or one without input:). */
    static final TqlErrorCode UNKNOWN_ACTION = new TqlErrorCode(TqlDomain.VIEW, 3303);
    /** TQL-VIEW-3306: unknown slot name for the view kind (customization ladder L1). */
    static final TqlErrorCode UNKNOWN_SLOT = new TqlErrorCode(TqlDomain.VIEW, 3306);
    /** TQL-VIEW-3308: a children: entry names a source the route does not declare. */
    static final TqlErrorCode UNKNOWN_SOURCE = new TqlErrorCode(TqlDomain.VIEW, 3308);

    private final ViewSpec spec;
    private final String entryTemplate;
    private final List<ViewFields.FieldDef> fields;
    private final Map<String, String> slots;
    private final Path appHome;

    private ViewBinding(ViewSpec spec, String entryTemplate, List<ViewFields.FieldDef> fields,
            Map<String, String> slots, Path appHome) {
        this.spec = spec;
        this.entryTemplate = entryTemplate;
        this.fields = fields;
        this.slots = slots;
        this.appHome = appHome;
    }

    /**
     * Resolves and validates a route's view reference at build time. {@code route} is the
     * declaring route (its {@code queries:} keys anchor a detail view's {@code children:});
     * {@code postRouteByPath} looks up the POST route serving a path (the form's
     * {@code action:}).
     */
    public static ViewBinding of(Path appHome, Path routeDir, String viewRef,
            RouteDefinition route, Function<String, RouteDefinition> postRouteByPath) {
        Path home = appHome.toAbsolutePath().normalize();
        Path file = resolve(home, routeDir, viewRef);
        ViewSpec spec = ViewSpec.parse(file);
        List<ViewFields.FieldDef> fields = List.of();
        if (ViewSpec.FORM.equals(spec.view())) {
            RouteDefinition action = postRouteByPath.apply(spec.action());
            if (action == null) {
                throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action "
                        + spec.action() + " matches no POST route");
            }
            if (action.input() == null || action.input().isEmpty()) {
                throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action route "
                        + action.id() + " declares no input: block to derive fields from");
            }
            fields = ViewFields.derive(viewRef, spec, action.input());
        }
        for (ViewSpec.Child child : spec.children()) {
            if (!"sql".equals(child.source())
                    && (route == null || !route.queries().containsKey(child.source()))) {
                throw new TqlException(UNKNOWN_SOURCE, "View " + viewRef + ": children source "
                        + child.source() + " is not a named query of the route");
            }
        }
        for (ViewSpec.Panel panel : spec.panels()) {
            String panelSource = panelSource(panel);
            if (!"sql".equals(panelSource)
                    && (route == null || !route.queries().containsKey(panelSource))) {
                throw new TqlException(UNKNOWN_SOURCE, "View " + viewRef + ": panel source "
                        + panelSource + " is not a named query of the route");
            }
        }
        String entry = spec.template() != null
                ? HtmlResponseRenderer.resolveTemplate(home, routeDir, spec.template())
                : "tql/view/" + spec.view();
        return new ViewBinding(spec, entry, fields, resolveSlots(home, routeDir, spec), home);
    }

    /**
     * Validates slot names against the view kind's offering (L1) and resolves each fragment
     * reference ({@code <template> :: <fragment>}, the template resolved colocated-first like
     * any other) into the engine-relative form the pattern inserts via preprocessing.
     */
    private static Map<String, String> resolveSlots(Path appHome, Path routeDir, ViewSpec spec) {
        if (spec.slots().isEmpty()) {
            return Map.of();
        }
        java.util.Set<String> allowed = ViewSpec.slotsFor(spec.view());
        Map<String, String> resolved = new LinkedHashMap<>();
        spec.slots().forEach((name, ref) -> {
            if (!allowed.contains(name)) {
                throw new TqlException(UNKNOWN_SLOT, "View " + spec.id() + ": unknown slot "
                        + name + " (a " + spec.view() + " view offers " + allowed + ")");
            }
            int separator = ref.indexOf("::");
            if (separator < 1) {
                throw new TqlException(ViewSpec.INVALID_VIEW, "View " + spec.id() + ": slot "
                        + name + " must reference '<template> :: <fragment>', got: " + ref);
            }
            String template = ref.substring(0, separator).trim();
            String fragment = ref.substring(separator + 2).trim();
            String engineName = HtmlResponseRenderer.resolveTemplate(appHome, routeDir, template);
            resolved.put(name, engineName + " :: " + fragment);
        });
        return Map.copyOf(resolved);
    }

    /** The template name the renderer feeds to the engine (pattern or per-view retarget). */
    public String entryTemplate() {
        return entryTemplate;
    }

    public ViewSpec spec() {
        return spec;
    }

    /** Resolves the view file like a template: colocated, then templates/, app-home-confined. */
    private static Path resolve(Path appHome, Path routeDir, String viewRef) {
        Path colocated = routeDir.toAbsolutePath().normalize().resolve(viewRef).normalize();
        Path file = Files.isRegularFile(colocated)
                ? colocated
                : appHome.resolve("templates").resolve(viewRef).normalize();
        if (!file.startsWith(appHome)) {
            throw new TqlException(UNRESOLVED_VIEW, "View escapes app home: " + viewRef);
        }
        if (!Files.isRegularFile(file)) {
            throw new TqlException(UNRESOLVED_VIEW, "View not found: " + viewRef);
        }
        return file;
    }

    /**
     * Assembles the render-time view model {@code v} (public API, docs/declarative-views.md)
     * against the execution context: resolved title/labels (message catalog, humanized
     * fallback), the form fields with prefill values, or the list columns and cell matrix.
     */
    public Map<String, Object> model(Map<String, Object> context, Locale locale) {
        return model(context, locale, "");
    }

    /**
     * The live-view attribute set (docs/realtime.md): with {@code refreshOn:}, the list's table
     * region carries the htmx sse wiring — connect to {@code /_tesseraql/topics} for the one
     * topic, and on that named event re-GET the page itself, selecting the same region the
     * search box already refreshes (the stream carries no data; the refetch is the ordinary,
     * fully-authorized route). Empty strings drop the attributes entirely via {@code th:attr}.
     */
    private void live(Map<String, Object> v, String pagePath) {
        String topic = spec.refreshOn() == null ? "" : spec.refreshOn().trim();
        boolean on = !topic.isEmpty();
        v.put("liveExt", on ? "sse" : "");
        v.put("liveConnect", on
                ? "/_tesseraql/topics?topics=" + java.net.URLEncoder.encode(topic,
                        java.nio.charset.StandardCharsets.UTF_8)
                : "");
        v.put("liveGet", on ? pagePath : "");
        v.put("liveTrigger", on ? "sse:" + topic : "");
        v.put("liveSelect", on ? "#" + spec.id() + "-table" : "");
        v.put("liveInclude", on ? "#" + spec.id() + "-table input[type='hidden']" : "");
        v.put("liveTarget", on ? "this" : "");
        v.put("liveSwap", on ? "outerHTML" : "");
    }

    /** {@code pagePath} is the request path sort/search links resolve against. */
    public Map<String, Object> model(Map<String, Object> context, Locale locale,
            String pagePath) {
        MessageCatalog catalog = MessageCatalog.live(appHome.resolve("messages"))
                .withFallback(I18nSettings.builtinCatalog());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", spec.id());
        v.put("kind", spec.view());
        v.put("title", message(catalog, locale, spec.title(),
                spec.title() == null ? humanize(spec.id()) : spec.title()));
        v.put("slots", slots);
        Map<String, Object> data = sourceOf(context, spec.source());
        if (ViewSpec.FORM.equals(spec.view())) {
            // A per-record action (/items/{id}/update) resolves its placeholders against the
            // request's path and coerced params, so one form view serves every record.
            v.put("action", interpolateAction(spec.action(), context));
            v.put("formId", spec.id().replace('.', '-') + "-form");
            Map<String, Object> row = firstRow(data);
            v.put("row", row);
            v.put("notFound", context.containsKey(spec.source()) && rows(data).isEmpty());
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (ViewFields.FieldDef field : fields) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", field.name());
                f.put("label", message(catalog, locale, field.labelKey(), field.labelFallback()));
                f.put("widget", field.widget());
                f.put("required", field.required());
                f.put("maxLength", field.maxLength());
                f.put("min", field.min());
                f.put("max", field.max());
                f.put("options", field.options());
                f.put("step", field.step());
                Object value = field.valueFrom(row);
                f.put("value", value == null ? "" : String.valueOf(value));
                rendered.add(f);
            }
            v.put("fields", rendered);
        } else if (ViewSpec.DETAIL.equals(spec.view())) {
            Map<String, Object> row = firstRow(data);
            v.put("row", row);
            v.put("notFound", context.containsKey(spec.source()) && rows(data).isEmpty());
            v.put("fields", detailFields(catalog, locale, row));
            List<Map<String, Object>> children = new ArrayList<>();
            for (ViewSpec.Child child : spec.children()) {
                List<Map<String, Object>> childRows = rows(sourceOf(context, child.source()));
                List<ViewSpec.Column> columns = columnsOf(child.columns(), childRows);
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("title", message(catalog, locale,
                        child.title() != null
                                ? child.title()
                                : "view." + spec.id() + "." + child.source(),
                        child.title() != null ? child.title() : humanize(child.source())));
                c.put("columns", renderedColumns(catalog, locale, columns));
                c.put("rows", cellMatrix(columns, childRows));
                children.add(c);
            }
            v.put("children", children);
        } else if (ViewSpec.DASHBOARD.equals(spec.view())) {
            List<Map<String, Object>> panels = new ArrayList<>();
            for (int index = 0; index < spec.panels().size(); index++) {
                ViewSpec.Panel panel = spec.panels().get(index);
                List<Map<String, Object>> rows = rows(sourceOf(context, panelSource(panel)));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", index);
                m.put("type", panel.type());
                String title = message(catalog, locale,
                        panel.title() != null
                                ? panel.title()
                                : "view." + spec.id() + ".panel" + (index + 1),
                        panel.title() != null ? panel.title() : humanize(panelSource(panel)));
                m.put("title", title);
                switch (panel.type()) {
                    case "stat" -> {
                        Object value = rows.isEmpty() ? null : rows.get(0).get(panel.column());
                        m.put("value", value == null ? "\u2014" : String.valueOf(value));
                    }
                    case "sparkline" -> {
                        List<Double> values = numbers(rows, panel.column());
                        // Not "values": OGNL resolves map.values to Map#values(), not the key.
                        m.put("series", values.stream().map(ViewBinding::plain)
                                .reduce((a, c) -> a + "," + c).orElse(""));
                        m.put("min", "0");
                        m.put("max", plain(values.stream().mapToDouble(Double::doubleValue)
                                .max().orElse(1)));
                    }
                    case "chart" -> {
                        List<String> labels = new ArrayList<>();
                        for (Map<String, Object> row : rows) {
                            Object label = row.get(panel.x());
                            labels.add(label == null ? "" : String.valueOf(label));
                        }
                        m.put("svg", io.tesseraql.yaml.view.ChartSvg.render(
                                panel.kind() == null ? "bar" : panel.kind(), labels,
                                numbers(rows, panel.y()), title));
                    }
                    case "table" -> {
                        List<ViewSpec.Column> columns = columnsOf(panel.columns(), rows);
                        m.put("columns", renderedColumns(catalog, locale, columns));
                        m.put("rows", cellMatrix(columns, rows));
                    }
                    default -> throw new IllegalStateException(panel.type());
                }
                panels.add(m);
            }
            v.put("panels", panels);
        } else {
            List<Map<String, Object>> rows = rows(data);
            List<ViewSpec.Column> columns = columnsOf(spec.columns(), rows);
            Map<String, Object> params = params(context);
            v.put("path", pagePath);
            live(v, pagePath);
            v.put("page", pager(context, params, pagePath));
            String sort = str(params.get("sort"));
            String dir = str(params.get("dir"));
            v.put("sort", sort);
            v.put("dir", dir);
            if (spec.search() != null) {
                Map<String, Object> search = new LinkedHashMap<>();
                search.put("param", spec.search());
                search.put("value", str(params.get(spec.search())));
                v.put("search", search);
            }
            List<Map<String, Object>> rendered = renderedColumns(catalog, locale, columns);
            for (int i = 0; i < columns.size(); i++) {
                ViewSpec.Column column = columns.get(i);
                if (!column.isSortable()) {
                    continue;
                }
                Map<String, Object> c = rendered.get(i);
                boolean active = column.name().equals(sort);
                boolean descending = active && "desc".equals(dir);
                c.put("sortable", true);
                c.put("ariaSort", active ? (descending ? "descending" : "ascending") : "none");
                c.put("sortHref", pagePath + "?sort=" + column.name() + "&dir="
                        + (active && !descending ? "desc" : "asc"));
            }
            v.put("columns", rendered);
            v.put("rows", cellMatrix(columns, rows));
        }
        return v;
    }

    /** A panel's context source: its {@code source:} or the main {@code sql} result. */
    private static String panelSource(ViewSpec.Panel panel) {
        return panel.source() == null || panel.source().isBlank() ? "sql" : panel.source();
    }

    /** A numeric series: the column's values over the rows (non-numbers parse or drop to 0). */
    private static List<Double> numbers(List<Map<String, Object>> rows, String column) {
        List<Double> values = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object raw = row.get(column);
            if (raw instanceof Number number) {
                values.add(number.doubleValue());
            } else if (raw != null) {
                try {
                    values.add(Double.parseDouble(String.valueOf(raw)));
                } catch (NumberFormatException ex) {
                    values.add(0.0);
                }
            } else {
                values.add(0.0);
            }
        }
        return values;
    }

    /** {@code 3.0} renders {@code 3}; fractions keep their point. */
    private static String plain(double value) {
        return value == Math.floor(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    /**
     * The list pattern's pager model (roadmap Phase 41): the `page` context entry the SQL
     * producer published, extended with self-rendering prev/next hrefs that keep the search
     * and sort state. Null when the route declares no page: block.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> pager(Map<String, Object> context, Map<String, Object> params,
            String pagePath) {
        Object raw = context.get("page");
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> pager = new LinkedHashMap<>((Map<String, Object>) raw);
        long number = pager.get("number") instanceof Number n ? n.longValue() : 1;
        StringBuilder state = new StringBuilder();
        for (String key : List.of("sort", "dir")) {
            String value = str(params.get(key));
            if (!value.isEmpty()) {
                state.append('&').append(key).append('=').append(encode(value));
            }
        }
        if (spec.search() != null) {
            String value = str(params.get(spec.search()));
            if (!value.isEmpty()) {
                state.append('&').append(spec.search()).append('=').append(encode(value));
            }
        }
        Object next = pager.get("next");
        if (Boolean.TRUE.equals(pager.get("hasNext"))) {
            pager.put("nextHref", next != null
                    ? pagePath + "?after=" + encode(String.valueOf(next)) + state
                    : pagePath + "?page=" + (number + 1) + state);
        }
        if (next == null && number > 1) {
            pager.put("prevHref", pagePath + "?page=" + (number - 1) + state);
        }
        return pager;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The coerced request params ({@code sort}/{@code dir}/the search input) or empty. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> params(Map<String, Object> context) {
        Object raw = context.get("params");
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Resolves {@code {placeholder}} segments of a form action against the request's path
     * params first, then the coerced inputs.
     */
    @SuppressWarnings("unchecked")
    private static String interpolateAction(String action, Map<String, Object> context) {
        if (action == null || !action.contains("{")) {
            return action;
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(params(context));
        Object path = context.get("path");
        if (path instanceof Map) {
            merged.putAll((Map<String, Object>) path);
        }
        return HtmlResponseRenderer.interpolateString(action, new EvaluationContext(merged));
    }

    /** A detail view's labelled values: explicit {@code fields:}, else the row's own columns. */
    private List<Map<String, Object>> detailFields(MessageCatalog catalog, Locale locale,
            Map<String, Object> row) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        List<ViewSpec.Field> selection = spec.fields();
        if (selection.isEmpty()) {
            selection = new ArrayList<>();
            for (String name : row.keySet()) {
                selection.add(new ViewSpec.Field(name, null, null, null));
            }
        }
        for (ViewSpec.Field field : selection) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", field.name());
            f.put("label", message(catalog, locale,
                    field.label() != null
                            ? field.label()
                            : "view." + spec.id() + "." + field.name(),
                    field.label() != null ? field.label() : humanize(field.name())));
            Object value = row.get(field.name());
            f.put("value", value == null ? "" : String.valueOf(value));
            rendered.add(f);
        }
        return rendered;
    }

    /** The column headers, labels resolved through the catalog with humanized fallbacks. */
    private List<Map<String, Object>> renderedColumns(MessageCatalog catalog, Locale locale,
            List<ViewSpec.Column> columns) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ViewSpec.Column column : columns) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", column.name());
            c.put("label", message(catalog, locale,
                    column.label() != null
                            ? column.label()
                            : "view." + spec.id() + "." + column.name(),
                    column.label() != null ? column.label() : humanize(column.name())));
            rendered.add(c);
        }
        return rendered;
    }

    /** The cell matrix: text per column per row, links resolved against the row's values. */
    private static List<List<Map<String, Object>>> cellMatrix(List<ViewSpec.Column> columns,
            List<Map<String, Object>> rows) {
        List<List<Map<String, Object>>> cells = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            EvaluationContext rowContext = new EvaluationContext(row);
            List<Map<String, Object>> line = new ArrayList<>();
            for (ViewSpec.Column column : columns) {
                Map<String, Object> cell = new LinkedHashMap<>();
                Object value = row.get(column.name());
                cell.put("text", column.text() != null
                        ? column.text()
                        : value == null ? "" : String.valueOf(value));
                cell.put("button", column.text() != null);
                cell.put("href", column.link() == null
                        ? null
                        : HtmlResponseRenderer.interpolateString(column.link(), rowContext));
                line.add(cell);
            }
            cells.add(line);
        }
        return cells;
    }

    /** A context entry carrying a {@code {rows, rowCount}} result (main {@code sql} or named). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sourceOf(Map<String, Object> context, String name) {
        Object raw = context.get(name);
        return raw instanceof Map ? (Map<String, Object>) raw : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> data) {
        Object raw = data.get("rows");
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object element : list) {
            if (element instanceof Map) {
                rows.add((Map<String, Object>) element);
            }
        }
        return rows;
    }

    private static Map<String, Object> firstRow(Map<String, Object> data) {
        List<Map<String, Object>> rows = rows(data);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    /** Explicit columns, else the result set's own columns in authored SQL order. */
    private static List<ViewSpec.Column> columnsOf(List<ViewSpec.Column> explicit,
            List<Map<String, Object>> rows) {
        if (!explicit.isEmpty()) {
            return explicit;
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<ViewSpec.Column> derived = new ArrayList<>();
        for (String name : rows.get(0).keySet()) {
            derived.add(new ViewSpec.Column(name, null, null, null, null));
        }
        return derived;
    }

    /** Message-catalog lookup: exact tag, then bare language, then the fallback text. */
    private static String message(MessageCatalog catalog, Locale locale, String key,
            String fallback) {
        if (key == null) {
            return fallback;
        }
        String exact = catalog.forLocale(locale.toLanguageTag()).get(key);
        if (exact != null) {
            return exact;
        }
        String language = catalog.forLocale(locale.getLanguage()).get(key);
        return language != null ? language : fallback;
    }

    private static String humanize(String name) {
        return ViewFields.humanize(name);
    }
}
