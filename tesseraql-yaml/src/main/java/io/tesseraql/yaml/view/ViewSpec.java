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
        List<Field> fields, List<Column> columns, String template) {

    /** Structurally invalid view document (docs/declarative-views.md, TQL-VIEW-3301). */
    public static final TqlErrorCode INVALID_VIEW = new TqlErrorCode(TqlDomain.VIEW, 3301);

    public static final String LIST = "list";
    public static final String FORM = "form";

    /** The slice-1 widget vocabulary (docs/declarative-views.md, TQL-VIEW-3305). */
    public static final java.util.Set<String> WIDGETS = java.util.Set.of("text", "textarea",
            "number", "date", "datetime-local", "checkbox", "select", "hidden");

    /** Presentation override for a form field derived from the action route's input block. */
    public record Field(String name, String label, String widget) {
    }

    /** A list column: selects, orders, and decorates a result-set column. */
    public record Column(String name, String label, String link) {
    }

    public ViewSpec {
        source = source == null || source.isBlank() ? "sql" : source;
        fields = fields == null ? List.of() : List.copyOf(fields);
        columns = columns == null ? List.of() : List.copyOf(columns);
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
        if (!LIST.equals(view) && !FORM.equals(view)) {
            throw invalid(name, "view must be '" + LIST + "' or '" + FORM + "', got: " + view);
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
                parseFields(name, tree.get("fields")), parseColumns(name, tree.get("columns")),
                str(tree.get("template")));
    }

    private static List<Field> parseFields(String source, Object raw) {
        List<Field> fields = new ArrayList<>();
        for (Map<String, Object> entry : entries(source, raw, "fields")) {
            String name = str(entry.get("name"));
            if (name == null || name.isBlank()) {
                throw invalid(source, "a fields: entry requires name:");
            }
            fields.add(new Field(name, str(entry.get("label")), str(entry.get("widget"))));
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
            columns.add(new Column(name, str(entry.get("label")), str(entry.get("link"))));
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
