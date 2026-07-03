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
            v.put("action", spec.action());
            v.put("formId", spec.id().replace('.', '-') + "-form");
            Map<String, Object> row = firstRow(data);
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
                Object value = row.get(field.name());
                f.put("value", value == null ? "" : String.valueOf(value));
                rendered.add(f);
            }
            v.put("fields", rendered);
        } else if (ViewSpec.DETAIL.equals(spec.view())) {
            v.put("fields", detailFields(catalog, locale, firstRow(data)));
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
        } else {
            List<Map<String, Object>> rows = rows(data);
            List<ViewSpec.Column> columns = columnsOf(spec.columns(), rows);
            v.put("columns", renderedColumns(catalog, locale, columns));
            v.put("rows", cellMatrix(columns, rows));
        }
        return v;
    }

    /** A detail view's labelled values: explicit {@code fields:}, else the row's own columns. */
    private List<Map<String, Object>> detailFields(MessageCatalog catalog, Locale locale,
            Map<String, Object> row) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        List<ViewSpec.Field> selection = spec.fields();
        if (selection.isEmpty()) {
            selection = new ArrayList<>();
            for (String name : row.keySet()) {
                selection.add(new ViewSpec.Field(name, null, null));
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
                cell.put("text", value == null ? "" : String.valueOf(value));
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

    private static String humanize(String name) {
        return ViewFields.humanize(name);
    }
}
