package io.tesseraql.yaml.view;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A declarative view document (roadmap Phase 39, docs/declarative-views.md): a {@code kind: view}
 * YAML file colocated with its route (the {@code *.view.yml} convention) and referenced by
 * {@code response.html.view}, rendering the route's data through the framework's
 * {@code tql/view/*} Hypermedia Components patterns instead of a hand-written template.
 *
 * <p>Parsed and structurally validated at build time (fail-fast). A form view does not redeclare
 * its fields: they derive from the {@code action:} route's {@code input:} block, so the HTML
 * constraints and the server-side validation are the same declaration; {@code fields:} entries
 * only select, order, and decorate. A list view with no {@code columns:} renders the result set's
 * own columns in authored SQL order.
 */
public record ViewSpec(String id, String view, String title, String action, String source,
        String search, List<Field> fields, List<Column> columns, List<Child> children,
        List<Panel> panels, Map<String, String> slots, String template, String refreshOn) {

    /** Structurally invalid view document (docs/declarative-views.md, TQL-VIEW-3301). */
    public static final TqlErrorCode INVALID_VIEW = new TqlErrorCode(TqlDomain.VIEW, 3301);

    /**
     * Chart-panel vocabulary violation (docs/analytics-experience.md track 2,
     * TQL-VIEW-3313): an unknown {@code chart:}, {@code y:} and {@code series:} together (or
     * neither), a {@code mark:} outside {@code chart: combo}, or a malformed passthrough
     * attribute ({@code xType:}, {@code height:}).
     */
    public static final TqlErrorCode INVALID_CHART = new TqlErrorCode(TqlDomain.VIEW, 3313);

    /**
     * Unknown key in a view document (docs/view-composition.md wave 0, TQL-VIEW-3314). View
     * documents are strict at every nesting level — the same posture the {@code domains/}
     * documents take — because a silently dropped key renders a page that quietly ignores
     * what the author wrote.
     */
    public static final TqlErrorCode UNKNOWN_KEY = new TqlErrorCode(TqlDomain.VIEW, 3314);

    public static final String LIST = "list";
    public static final String FORM = "form";
    public static final String DETAIL = "detail";
    public static final String DASHBOARD = "dashboard";

    /** The dashboard panel types (docs/declarative-views.md; {@code view} embeds a view). */
    public static final java.util.Set<String> PANEL_TYPES = java.util.Set.of("stat",
            "sparkline", "chart", "table", "view");

    /**
     * The chart kinds — the Hypermedia Components {@code data-hc-chart} vocabulary the panel
     * passes through (docs/analytics-experience.md track 2; {@code histogram} and
     * {@code heatmap} stay out until a gallery app needs them).
     */
    public static final java.util.Set<String> CHART_KINDS = java.util.Set.of("bar", "line",
            "area", "combo", "bar-stacked", "bar-grouped", "scatter");

    /** Per-series marks, legal only under {@code chart: combo} (the kit's {@code data-mark}). */
    private static final java.util.Set<String> SERIES_MARKS = java.util.Set.of("bar", "line",
            "area");

    /** The {@code xType:} passthrough values (the kit's {@code data-x-type}). */
    private static final java.util.Set<String> X_TYPES = java.util.Set.of("category", "number",
            "date");

    /** The slice-1 widget vocabulary (docs/declarative-views.md, TQL-VIEW-3305). */
    public static final java.util.Set<String> WIDGETS = java.util.Set.of("text", "textarea",
            "number", "date", "datetime-local", "checkbox", "select", "hidden");

    /** The strict key vocabulary per nesting level (TQL-VIEW-3314). */
    private static final java.util.Set<String> DOCUMENT_KEYS = java.util.Set.of("version",
            "kind", "id", "recipe", "title", "action", "source", "search", "fields", "columns",
            "children", "panels", "slots", "template", "refreshOn");
    private static final java.util.Set<String> FIELD_KEYS = java.util.Set.of("name", "label",
            "widget", "column", "domain");
    private static final java.util.Set<String> COLUMN_KEYS = java.util.Set.of("name", "label",
            "link", "sortable", "text", "domain");
    private static final java.util.Set<String> CHILD_KEYS = java.util.Set.of("source", "title",
            "columns", "view");
    private static final java.util.Set<String> PANEL_KEYS = java.util.Set.of("title", "type",
            "source", "column", "x", "y", "chart", "series", "xType", "height", "legend",
            "yLabel", "columns", "view");
    private static final java.util.Set<String> SERIES_KEYS = java.util.Set.of("column", "label",
            "mark");

    /**
     * The keys a view document may declare, for the guard that keeps the shipped JSON Schema
     * equal to what this loader accepts. The schema documented a {@code view:} property this
     * parser never read while rejecting the {@code recipe:} it does read, so every shipped view
     * document was schema-invalid; reading the loader's own vocabulary is what makes the next
     * such divergence fail the build.
     */
    public static java.util.Set<String> documentKeys() {
        return DOCUMENT_KEYS;
    }

    /** The recipe values a view document may declare. */
    public static java.util.Set<String> recipes() {
        return java.util.Set.of(LIST, FORM, DETAIL, DASHBOARD);
    }

    /**
     * The slot names each view kind offers (customization ladder L1, TQL-VIEW-3306): list and
     * detail pages take {@code header}/{@code footer}; a form additionally takes {@code actions}
     * beside its submit button.
     */
    public static java.util.Set<String> slotsFor(String view) {
        return FORM.equals(view)
                ? java.util.Set.of("header", "footer", "actions")
                : java.util.Set.of("header", "footer");
    }

    /**
     * Presentation override for a form field derived from the action route's input block.
     * {@code column} names the result-set column the prefill value reads when it differs from
     * the input name (the camelCase-input over snake_case-column convention falls back
     * automatically). {@code domain} references an app-level field domain
     * (docs/view-composition.md wave 3a) — the explicit read-side link that brings the
     * domain's presentation and data-classification knowledge to a rendered field.
     */
    public record Field(String name, String label, String widget, String column,
            String domain) {
    }

    /**
     * A list column: selects, orders, and decorates a result-set column. {@code sortable}
     * renders the header as a server-driven sort link (the route must declare {@code sort}/
     * {@code dir} inputs its SQL applies); {@code text} renders that literal (styled as a small
     * button when linked) instead of the row value — the per-row action column.
     */
    public record Column(String name, String label, String link, Boolean sortable, String text,
            String domain) {

        public boolean isSortable() {
            return Boolean.TRUE.equals(sortable);
        }
    }

    /**
     * A detail view's child: a named query rendered through the shared table pattern (the
     * inline {@code columns:} shorthand), or — with {@code view:} — an embedded view document
     * (docs/view-composition.md wave 2b) whose data comes from this route's context; the
     * entry's {@code source:} overrides the embedded document's own.
     */
    public record Child(String source, String title, List<Column> columns, String view) {
        public Child {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /**
     * One charted series: the numeric {@code column} plotted per row, an optional display
     * {@code label} (message-key-first like every label), and — under {@code chart: combo}
     * only — the {@code mark} the kit draws it with ({@code bar}/{@code line}/{@code area}).
     */
    public record Series(String column, String label, String mark) {
    }

    /**
     * A dashboard panel over one of the route's results: a {@code stat} (one value), a
     * {@code sparkline} over a {@code column}, a {@code chart} in the kit's
     * {@code data-hc-chart} vocabulary ({@code x} plus {@code y} or multi-column
     * {@code series}; {@code xType}/{@code height}/{@code legend}/{@code yLabel} pass through
     * as the kit's data attributes), or an embedded {@code table}.
     */
    public record Panel(String title, String type, String source, String column, String x,
            String y, String kind, List<Series> series, String xType, Integer height,
            Boolean legend, String yLabel, List<Column> columns, String view) {
        public Panel {
            columns = columns == null ? List.of() : List.copyOf(columns);
            series = series == null ? List.of() : List.copyOf(series);
        }

        /** The charted series: the explicit {@code series:} list, else the {@code y:} shorthand. */
        public List<Series> effectiveSeries() {
            if (!series.isEmpty()) {
                return series;
            }
            return y == null ? List.of() : List.of(new Series(y, null, null));
        }
    }

    public ViewSpec {
        source = source == null || source.isBlank() ? "main" : source;
        fields = fields == null ? List.of() : List.copyOf(fields);
        columns = columns == null ? List.of() : List.copyOf(columns);
        children = children == null ? List.of() : List.copyOf(children);
        panels = panels == null ? List.of() : List.copyOf(panels);
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    /**
     * Parses and structurally validates a view document. The id defaults from the file name
     * ({@code new.view.yml} &rarr; {@code new}); a form must name its {@code action:} route (the
     * command the form posts to and the source of its field definitions).
     */
    public static ViewSpec parse(Path file) {
        Map<String, Object> tree = new SimpleYamlParser().parseTree(file);
        String name = file.getFileName().toString();
        if (!"view".equals(tree.get("kind"))) {
            throw invalid(name, "kind must be 'view'");
        }
        // The version discriminator is required on every document family
        // (docs/vocabulary-cleanup.md slice 2); views used to skip the check.
        if (!"tesseraql/v1".equals(tree.get("version"))) {
            throw invalid(name, "version must be 'tesseraql/v1'");
        }
        rejectUnknown(name, tree, DOCUMENT_KEYS, "a view document");
        String view = str(tree.get("recipe"));
        if (!LIST.equals(view) && !FORM.equals(view) && !DETAIL.equals(view)
                && !DASHBOARD.equals(view)) {
            throw invalid(name, "recipe must be '" + LIST + "', '" + FORM + "', '" + DETAIL
                    + "' or '" + DASHBOARD + "', got: " + view);
        }
        if (!DETAIL.equals(view) && tree.get("children") != null) {
            throw invalid(name, "children: is a detail-view key");
        }
        if (!DASHBOARD.equals(view) && tree.get("panels") != null) {
            throw invalid(name, "panels: is a dashboard-view key");
        }
        if (DASHBOARD.equals(view) && tree.get("panels") == null) {
            throw invalid(name, "a dashboard view must declare panels:");
        }
        String action = str(tree.get("action"));
        if (FORM.equals(view) && (action == null || action.isBlank())) {
            throw invalid(name, "a form view must declare action: (the command route it posts to)");
        }
        String id = str(tree.get("id"));
        if (id == null || id.isBlank()) {
            id = name.endsWith(".view.yml")
                    ? name.substring(0, name.length() - ".view.yml".length())
                    : name;
        }
        return new ViewSpec(id, view, str(tree.get("title")), action, str(tree.get("source")),
                str(tree.get("search")),
                parseFields(name, tree.get("fields")), parseColumns(name, tree.get("columns")),
                parseChildren(name, tree.get("children")), parsePanels(name, tree.get("panels")),
                parseSlots(name, tree.get("slots")), str(tree.get("template")),
                str(tree.get("refreshOn")));
    }

    private static List<Panel> parsePanels(String source, Object raw) {
        List<Panel> panels = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "panels")) {
            rejectUnknown(source, entry, PANEL_KEYS, "a panels: entry");
            String type = str(entry.get("type"));
            if (type == null || !PANEL_TYPES.contains(type)) {
                throw invalid(source, "a panels: entry requires type: one of " + PANEL_TYPES);
            }
            String column = str(entry.get("column"));
            if (("stat".equals(type) || "sparkline".equals(type))
                    && (column == null || column.isBlank())) {
                throw invalid(source, "a " + type + " panel requires column:");
            }
            String embeddedView = str(entry.get("view"));
            if ("view".equals(type) && (embeddedView == null || embeddedView.isBlank())) {
                throw invalid(source, "a view panel requires view: (the embedded view id)");
            }
            if (!"view".equals(type) && embeddedView != null) {
                throw invalid(source, "view: is a view-panel key (type: view)");
            }
            String kind = str(entry.get("chart"));
            List<Series> series = parseSeries(source, entry.get("series"));
            String xType = str(entry.get("xType"));
            Integer height = parseHeight(source, entry.get("height"));
            Boolean legend = flag(source, entry.get("legend"), "legend");
            if ("chart".equals(type)) {
                validateChart(source, entry, kind, series, xType);
            } else if (entry.get("series") != null || xType != null || height != null
                    || legend != null || entry.get("yLabel") != null) {
                throw invalidChart(source, "series:, xType:, height:, legend: and yLabel: are"
                        + " chart-panel keys");
            }
            panels.add(new Panel(str(entry.get("title")), type, str(entry.get("source")),
                    column, str(entry.get("x")), str(entry.get("y")), kind, series, xType,
                    height, legend, str(entry.get("yLabel")),
                    parseColumns(source, entry.get("columns")), embeddedView));
        }
        return panels;
    }

    /** The chart-panel vocabulary (docs/analytics-experience.md track 2, TQL-VIEW-3313). */
    private static void validateChart(String source, Map<String, Object> entry, String kind,
            List<Series> series, String xType) {
        if (kind != null && !CHART_KINDS.contains(kind)) {
            throw invalidChart(source, "chart: must be one of " + CHART_KINDS + ", got: "
                    + kind);
        }
        if (str(entry.get("x")) == null) {
            throw invalidChart(source, "a chart panel requires x: (the label column)");
        }
        boolean hasY = str(entry.get("y")) != null;
        if (hasY && !series.isEmpty()) {
            throw invalidChart(source,
                    "a chart panel declares y: or series:, not both (y: is the one-series"
                            + " shorthand)");
        }
        if (!hasY && series.isEmpty()) {
            throw invalidChart(source, "a chart panel requires y: or series:");
        }
        boolean combo = "combo".equals(kind);
        for (Series charted : series) {
            if (charted.mark() != null && !combo) {
                throw invalidChart(source, "series mark: is legal only under chart: combo");
            }
            if (charted.mark() != null && !SERIES_MARKS.contains(charted.mark())) {
                throw invalidChart(source, "series mark: must be one of " + SERIES_MARKS
                        + ", got: " + charted.mark());
            }
        }
        if (combo && series.isEmpty()) {
            throw invalidChart(source, "chart: combo requires series: (each with its mark:)");
        }
        if (xType != null && !X_TYPES.contains(xType)) {
            throw invalidChart(source, "chart xType: must be one of " + X_TYPES + ", got: "
                    + xType);
        }
    }

    private static List<Series> parseSeries(String source, Object raw) {
        List<Series> series = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "series")) {
            rejectUnknown(source, entry, SERIES_KEYS, "a series: entry");
            String column = str(entry.get("column"));
            if (column == null || column.isBlank()) {
                throw invalidChart(source, "a series: entry requires column:");
            }
            series.add(new Series(column, str(entry.get("label")), str(entry.get("mark"))));
        }
        return series;
    }

    /**
     * A boolean view key, refused when it is not one.
     *
     * <p>{@code sortable}/{@code legend} were read with {@code instanceof Boolean … : null}, so
     * {@code sortable: "yes"} passed {@link #rejectUnknown} — the key is real — and then coerced
     * to null, silently rendering the column non-sortable. A wrong-typed value is exactly what
     * the {@code TQL-VIEW-3314} strictness promise is about; only the keys were being kept.
     */
    private static Boolean flag(String source, Object raw, String key) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean value) {
            return value;
        }
        throw invalid(source, key + ": must be true or false, got: " + raw);
    }

    private static Integer parseHeight(String source, Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Integer height && height > 0) {
            return height;
        }
        throw invalidChart(source, "chart height: must be a positive integer, got: " + raw);
    }

    private static List<Child> parseChildren(String source, Object raw) {
        List<Child> children = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "children")) {
            rejectUnknown(source, entry, CHILD_KEYS, "a children: entry");
            String childSource = str(entry.get("source"));
            String view = str(entry.get("view"));
            if ((childSource == null || childSource.isBlank())
                    && (view == null || view.isBlank())) {
                throw invalid(source, "a children: entry requires source: (a named query key)"
                        + " or view: (an embedded view id)");
            }
            if (view != null && entry.get("columns") != null) {
                throw invalid(source, "a children: entry with view: embeds that document —"
                        + " columns: belong inside it");
            }
            children.add(new Child(childSource, str(entry.get("title")),
                    parseColumns(source, entry.get("columns")), view));
        }
        return children;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseSlots(String source, Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(source, "slots: must be a mapping of slot name to fragment reference");
        }
        Map<String, String> slots = new java.util.LinkedHashMap<>();
        ((Map<String, Object>) map).forEach((key, value) -> {
            if (value == null || str(value).isBlank()) {
                throw invalid(source, "slot " + key + " requires a fragment reference");
            }
            slots.put(key, str(value));
        });
        return slots;
    }

    private static List<Field> parseFields(String source, Object raw) {
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "fields")) {
            rejectUnknown(source, entry, FIELD_KEYS, "a fields: entry");
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                throw invalid(source, "a fields: entry requires name:");
            }
            fields.add(new Field(name, str(entry.get("label")), str(entry.get("widget")),
                    str(entry.get("column")), str(entry.get("domain"))));
        }
        return fields;
    }

    private static List<Column> parseColumns(String source, Object raw) {
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "columns")) {
            rejectUnknown(source, entry, COLUMN_KEYS, "a columns: entry");
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                throw invalid(source, "a columns: entry requires name:");
            }
            columns.add(new Column(name, str(entry.get("label")), str(entry.get("link")),
                    flag(source, entry.get("sortable"), "sortable"),
                    str(entry.get("text")), str(entry.get("domain"))));
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(String source, Object raw, String key) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            throw invalid(source, key + ": must be a list");
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object element : list) {
            if (!(element instanceof Map)) {
                throw invalid(source, "each " + key + ": entry must be a mapping");
            }
            maps.add((Map<String, Object>) element);
        }
        return maps;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * View documents are strict at every nesting level (TQL-VIEW-3314): an unknown key is a
     * build error, never silently dropped.
     */
    private static void rejectUnknown(String source, Map<String, Object> map,
            java.util.Set<String> allowed, String where) {
        for (String key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new TqlException(UNKNOWN_KEY, "Invalid view document " + source + ": "
                        + where + " does not accept " + key + ": (accepted: "
                        + new java.util.TreeSet<>(allowed) + ")");
            }
        }
    }

    private static TqlException invalid(String source, String message) {
        return new TqlException(INVALID_VIEW, "Invalid view document " + source + ": " + message);
    }

    private static TqlException invalidChart(String source, String message) {
        return new TqlException(INVALID_CHART,
                "Invalid view document " + source + ": " + message);
    }
}
