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
        Map<String, String> slots, String template) {

    /** Structurally invalid view document (docs/declarative-views.md, TQL-VIEW-3301). */
    public static final TqlErrorCode INVALID_VIEW = new TqlErrorCode(TqlDomain.VIEW, 3301);

    public static final String LIST = "list";
    public static final String FORM = "form";
    public static final String DETAIL = "detail";

    /** The slice-1 widget vocabulary (docs/declarative-views.md, TQL-VIEW-3305). */
    public static final java.util.Set<String> WIDGETS = java.util.Set.of("text", "textarea",
            "number", "date", "datetime-local", "checkbox", "select", "hidden");

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
     * automatically).
     */
    public record Field(String name, String label, String widget, String column) {
    }

    /**
     * A list column: selects, orders, and decorates a result-set column. {@code sortable}
     * renders the header as a server-driven sort link (the route must declare {@code sort}/
     * {@code dir} inputs its SQL applies); {@code text} renders that literal (styled as a small
     * button when linked) instead of the row value — the per-row action column.
     */
    public record Column(String name, String label, String link, Boolean sortable, String text) {

        public boolean isSortable() {
            return Boolean.TRUE.equals(sortable);
        }
    }

    /** A detail view's child list: a named query composed under the parent record. */
    public record Child(String source, String title, List<Column> columns) {
        public Child {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    public ViewSpec {
        source = source == null || source.isBlank() ? "sql" : source;
        fields = fields == null ? List.of() : List.copyOf(fields);
        columns = columns == null ? List.of() : List.copyOf(columns);
        children = children == null ? List.of() : List.copyOf(children);
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
        String view = str(tree.get("view"));
        if (!LIST.equals(view) && !FORM.equals(view) && !DETAIL.equals(view)) {
            throw invalid(name, "view must be '" + LIST + "', '" + FORM + "' or '" + DETAIL
                    + "', got: " + view);
        }
        if (!DETAIL.equals(view) && tree.get("children") != null) {
            throw invalid(name, "children: is a detail-view key");
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
                parseChildren(name, tree.get("children")), parseSlots(name, tree.get("slots")),
                str(tree.get("template")));
    }

    private static List<Child> parseChildren(String source, Object raw) {
        List<Child> children = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "children")) {
            String childSource = str(entry.get("source"));
            if (childSource == null || childSource.isBlank()) {
                throw invalid(source, "a children: entry requires source: (a named query key)");
            }
            children.add(new Child(childSource, str(entry.get("title")),
                    parseColumns(source, entry.get("columns"))));
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
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                throw invalid(source, "a fields: entry requires name:");
            }
            fields.add(new Field(name, str(entry.get("label")), str(entry.get("widget")),
                    str(entry.get("column"))));
        }
        return fields;
    }

    private static List<Column> parseColumns(String source, Object raw) {
        List<Column> columns = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "columns")) {
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                throw invalid(source, "a columns: entry requires name:");
            }
            columns.add(new Column(name, str(entry.get("label")), str(entry.get("link")),
                    entry.get("sortable") instanceof Boolean b ? b : null,
                    str(entry.get("text"))));
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

    private static TqlException invalid(String source, String message) {
        return new TqlException(INVALID_VIEW, "Invalid view document " + source + ": " + message);
    }
}
