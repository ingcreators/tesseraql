package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.model.InputField;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.view.ViewSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    /** TQL-VIEW-3304: a fields: entry names an input the action route does not declare. */
    static final TqlErrorCode UNKNOWN_FIELD = new TqlErrorCode(TqlDomain.VIEW, 3304);
    /** TQL-VIEW-3305: unknown widget name. */
    static final TqlErrorCode UNKNOWN_WIDGET = new TqlErrorCode(TqlDomain.VIEW, 3305);

    /** The slice-1 widget vocabulary (docs/declarative-views.md), shared with the lint. */
    public static final Set<String> WIDGETS = ViewSpec.WIDGETS;

    private final ViewSpec spec;
    private final String entryTemplate;
    private final List<FieldDef> fields;
    private final Path appHome;

    /** A form field ready to render: the derived input constraints plus presentation. */
    record FieldDef(String name, String labelKey, String labelFallback, String widget,
            boolean required, Integer maxLength, Integer min, Integer max, List<String> options) {
    }

    private ViewBinding(ViewSpec spec, String entryTemplate, List<FieldDef> fields, Path appHome) {
        this.spec = spec;
        this.entryTemplate = entryTemplate;
        this.fields = fields;
        this.appHome = appHome;
    }

    /**
     * Resolves and validates a route's view reference at build time. {@code postRouteByPath}
     * looks up the POST route serving a path (the form's {@code action:}).
     */
    public static ViewBinding of(Path appHome, Path routeDir, String viewRef,
            Function<String, RouteDefinition> postRouteByPath) {
        Path home = appHome.toAbsolutePath().normalize();
        Path file = resolve(home, routeDir, viewRef);
        ViewSpec spec = ViewSpec.parse(file);
        List<FieldDef> fields = List.of();
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
            fields = deriveFields(viewRef, spec, action.input());
        }
        String entry = spec.template() != null
                ? HtmlResponseRenderer.resolveTemplate(home, routeDir, spec.template())
                : "tql/view/" + spec.view();
        return new ViewBinding(spec, entry, fields, home);
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
     * Derives the renderable fields from the action route's declared inputs: every writable
     * input in declared order, or — when {@code fields:} is present — that selection and order,
     * each entry merged over its derived definition.
     */
    private static List<FieldDef> deriveFields(String viewRef, ViewSpec spec,
            Map<String, InputField> inputs) {
        List<FieldDef> defs = new ArrayList<>();
        if (spec.fields().isEmpty()) {
            inputs.forEach((name, input) -> {
                if (input.isWritable()) {
                    defs.add(fieldDef(spec, name, input, null));
                }
            });
            return List.copyOf(defs);
        }
        for (ViewSpec.Field override : spec.fields()) {
            InputField input = inputs.get(override.name());
            if (input == null) {
                throw new TqlException(UNKNOWN_FIELD, "View " + viewRef + ": field "
                        + override.name() + " is not declared by the action route's input: block");
            }
            defs.add(fieldDef(spec, override.name(), input, override));
        }
        return List.copyOf(defs);
    }

    private static FieldDef fieldDef(ViewSpec spec, String name, InputField input,
            ViewSpec.Field override) {
        String widget = override == null || override.widget() == null
                ? defaultWidget(input)
                : override.widget();
        if (!WIDGETS.contains(widget)) {
            throw new TqlException(UNKNOWN_WIDGET, "View " + spec.id() + ": unknown widget "
                    + widget + " on field " + name + " (known: " + WIDGETS + ")");
        }
        String labelKey = override != null && override.label() != null
                ? override.label()
                : "view." + spec.id() + "." + name;
        String fallback = override != null && override.label() != null
                ? override.label()
                : humanize(name);
        List<String> options = input.enumValues() == null
                ? List.of()
                : List.copyOf(input.enumValues());
        return new FieldDef(name, labelKey, fallback, widget, input.required(),
                input.maxLength(), input.min(), input.max(), options);
    }

    /** The widget an input renders as when the view does not say otherwise. */
    private static String defaultWidget(InputField input) {
        if (input.enumValues() != null && !input.enumValues().isEmpty()) {
            return "select";
        }
        String type = input.type() == null ? "string" : input.type();
        return switch (type) {
            case "boolean" -> "checkbox";
            case "integer", "number" -> "number";
            case "date" -> "date";
            case "datetime" -> "datetime-local";
            default -> "text";
        };
    }

    /**
     * Assembles the render-time view model {@code v} (public API, docs/declarative-views.md)
     * against the execution context: resolved title/labels (message catalog, humanized
     * fallback), the form fields with prefill values, or the list columns and cell matrix.
     */
    public Map<String, Object> model(Map<String, Object> context, Locale locale) {
        MessageCatalog catalog = MessageCatalog.live(appHome.resolve("messages"))
                .withFallback(I18nSettings.builtinCatalog());
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", spec.id());
        v.put("kind", spec.view());
        v.put("title", message(catalog, locale, spec.title(),
                spec.title() == null ? humanize(spec.id()) : spec.title()));
        Map<String, Object> data = source(context);
        if (ViewSpec.FORM.equals(spec.view())) {
            v.put("action", spec.action());
            v.put("formId", spec.id().replace('.', '-') + "-form");
            Map<String, Object> row = firstRow(data);
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (FieldDef field : fields) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("name", field.name());
                f.put("label", message(catalog, locale, field.labelKey(), field.labelFallback()));
                f.put("widget", field.widget());
                f.put("required", field.required());
                f.put("maxLength", field.maxLength());
                f.put("min", field.min());
                f.put("max", field.max());
                f.put("options", field.options());
                Object value = row.get(field.name());
                f.put("value", value == null ? "" : String.valueOf(value));
                rendered.add(f);
            }
            v.put("fields", rendered);
        } else {
            List<Map<String, Object>> rows = rows(data);
            List<ViewSpec.Column> columns = columns(rows);
            List<Map<String, Object>> renderedColumns = new ArrayList<>();
            for (ViewSpec.Column column : columns) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", column.name());
                c.put("label", message(catalog, locale,
                        column.label() != null
                                ? column.label()
                                : "view." + spec.id() + "." + column.name(),
                        column.label() != null ? column.label() : humanize(column.name())));
                renderedColumns.add(c);
            }
            v.put("columns", renderedColumns);
            List<List<Map<String, Object>>> cells = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                EvaluationContext rowContext = new EvaluationContext(row);
                List<Map<String, Object>> line = new ArrayList<>();
                for (ViewSpec.Column column : columns) {
                    Map<String, Object> cell = new LinkedHashMap<>();
                    Object value = row.get(column.name());
                    cell.put("text", value == null ? "" : String.valueOf(value));
                    cell.put("href", column.link() == null
                            ? null
                            : HtmlResponseRenderer.interpolateString(column.link(), rowContext));
                    line.add(cell);
                }
                cells.add(line);
            }
            v.put("rows", cells);
        }
        return v;
    }

    /** The context entry the view reads (default {@code sql}): a {@code {rows, rowCount}} map. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> source(Map<String, Object> context) {
        Object raw = context.get(spec.source());
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
    private List<ViewSpec.Column> columns(List<Map<String, Object>> rows) {
        if (!spec.columns().isEmpty()) {
            return spec.columns();
        }
        if (rows.isEmpty()) {
            return List.of();
        }
        List<ViewSpec.Column> derived = new ArrayList<>();
        for (String name : rows.get(0).keySet()) {
            derived.add(new ViewSpec.Column(name, null, null));
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

    /** {@code login_id} / {@code unitPrice} &rarr; {@code Login id} / {@code Unit price}. */
    static String humanize(String name) {
        String spaced = name.replaceAll("[_\\-]+", " ")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2").trim().toLowerCase(Locale.ROOT);
        return spaced.isEmpty()
                ? name
                : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
