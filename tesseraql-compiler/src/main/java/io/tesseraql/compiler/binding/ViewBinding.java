package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.i18n.I18nSettings;
import io.tesseraql.yaml.i18n.MessageCatalog;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.view.ViewFields;
import io.tesseraql.yaml.view.ViewSpec;
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
    /** TQL-VIEW-3318: an embedded view embeds further — embedding depth is 1. */
    static final TqlErrorCode EMBED_DEPTH = new TqlErrorCode(TqlDomain.VIEW, 3318);
    /** TQL-VIEW-3322: a declared list key: column is null, absent or blank in a result row. */
    static final TqlErrorCode INVALID_ROW_KEY = new TqlErrorCode(TqlDomain.VIEW, 3322);

    /**
     * An embedded view (docs/view-composition.md wave 2b): the sub-binding plus the host
     * entry's optional {@code source:} override, remapped onto the embedded document's own
     * source key at model time.
     */
    private record Embed(ViewBinding binding, String sourceOverride) {
    }

    private final ViewSpec spec;
    private final String entryTemplate;
    private final List<ViewFields.FieldDef> fields;
    private final Map<String, String> slots;
    private final Path appHome;
    private final Map<Integer, Embed> childEmbeds;
    private final Map<Integer, Embed> panelEmbeds;
    private final Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies;
    private final Map<String, String> catalogByColumn;
    private final List<ViewFields.FieldDef> filterFields;

    private ViewBinding(ViewSpec spec, String entryTemplate, List<ViewFields.FieldDef> fields,
            Map<String, String> slots, Path appHome, Map<Integer, Embed> childEmbeds,
            Map<Integer, Embed> panelEmbeds,
            Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies,
            Map<String, String> catalogByColumn, List<ViewFields.FieldDef> filterFields) {
        this.spec = spec;
        this.entryTemplate = entryTemplate;
        this.fields = fields;
        this.slots = slots;
        this.appHome = appHome;
        this.childEmbeds = childEmbeds;
        this.panelEmbeds = panelEmbeds;
        this.readPolicies = readPolicies;
        this.catalogByColumn = catalogByColumn;
        this.filterFields = filterFields;
    }

    /**
     * The output policies the view's explicit {@code domain:} references carry
     * (docs/view-composition.md wave 3b), keyed by column/field name — embedded views'
     * included. The HTML renderer applies them to the execution context before model
     * assembly with the same {@code FieldPolicyApplier} the JSON renderer uses.
     */
    public Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies() {
        return readPolicies;
    }

    /**
     * Resolves and validates a route's view reference at build time. {@code viewRef} is the
     * view document's app-wide {@code id} (docs/view-composition.md wave 1), resolved through
     * {@code viewById} — the manifest's view registry. {@code route} is the declaring route
     * (its {@code sources:} keys anchor a detail view's {@code children:});
     * {@code postRouteByPath} looks up the POST route serving a path (the form's
     * {@code action:}). Slot and {@code template:} references inside the document resolve
     * against the view file's own directory, then {@code templates/} — a shared document
     * resolves the same fragments for every referencing route.
     */
    public static ViewBinding of(Path appHome, String viewRef, RouteDefinition route,
            Function<String, RouteDefinition> postRouteByPath, Function<String, Path> viewById) {
        Path home = appHome.toAbsolutePath().normalize();
        Path file = viewById.apply(viewRef);
        if (file == null) {
            throw new TqlException(UNRESOLVED_VIEW, "view: " + viewRef + " does not resolve to"
                    + " a view document id (ids come from *.view.yml under web/ or templates/;"
                    + " an unparseable document is not indexed — run lint)");
        }
        Path viewDir = file.getParent();
        ViewSpec spec = ViewSpec.parse(file);
        List<ViewFields.FieldDef> fields = ViewSpec.FORM.equals(spec.view())
                ? formFields(viewRef, spec, postRouteByPath)
                : List.of();
        Map<Integer, Embed> childEmbeds = childEmbeds(home, viewRef, spec, route,
                postRouteByPath, viewById);
        Map<Integer, Embed> panelEmbeds = panelEmbeds(home, viewRef, spec, route,
                postRouteByPath, viewById);
        ReadSide readSide = readSide(home, viewRef, spec, childEmbeds, panelEmbeds);
        // layout: page (docs/list-surface.md decision 1) renders through its own pattern file,
        // so the card pattern stays byte-identical while the grid page is opt-in; an app
        // overrides either the same way (templates/tql/view/list-page.html, ladder L2).
        String pattern = ViewSpec.LIST.equals(spec.view())
                && ViewSpec.LAYOUT_PAGE.equals(spec.effectiveLayout())
                        ? "list-page"
                        : spec.view();
        String entry = spec.template() != null
                ? TemplateResolution.resolve(home, viewDir, spec.template())
                : "tql/view/" + pattern;
        // The grid page's filter fields derive from the declaring route's own input: block
        // (docs/list-surface.md decision 6) — the same declaration the request binder coerces.
        List<ViewFields.FieldDef> filterFields = spec.filters().isEmpty()
                ? List.of()
                : ViewFields.deriveFilters(viewRef, spec,
                        route == null ? null : route.input());
        return new ViewBinding(spec, entry, fields, resolveSlots(home, viewDir, spec), home,
                Map.copyOf(childEmbeds), Map.copyOf(panelEmbeds), readSide.policies(),
                readSide.catalogs(), filterFields);
    }

    /**
     * A form's fields, derived from the {@code action:} route's {@code input:} block — the same
     * declaration {@link InputBinder} enforces server-side, so the rendered constraints and the
     * enforced ones can never differ.
     */
    private static List<ViewFields.FieldDef> formFields(String viewRef, ViewSpec spec,
            Function<String, RouteDefinition> postRouteByPath) {
        RouteDefinition action = postRouteByPath.apply(spec.action());
        if (action == null) {
            throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action "
                    + spec.action() + " matches no POST route");
        }
        if (action.input() == null || action.input().isEmpty()) {
            throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action route "
                    + action.id() + " declares no input: block to derive fields from");
        }
        return ViewFields.derive(viewRef, spec, action.input());
    }

    /**
     * The detail view's {@code children:} entries that embed a view, by index — and, along the
     * way, the guard that every child reading data names a source the route declares.
     */
    private static Map<Integer, Embed> childEmbeds(Path home, String viewRef, ViewSpec spec,
            RouteDefinition route, Function<String, RouteDefinition> postRouteByPath,
            Function<String, Path> viewById) {
        Map<Integer, Embed> childEmbeds = new LinkedHashMap<>();
        for (int index = 0; index < spec.children().size(); index++) {
            ViewSpec.Child child = spec.children().get(index);
            if (child.view() != null) {
                childEmbeds.put(index, embed(home, viewRef, child.view(), child.source(),
                        route, postRouteByPath, viewById));
                if (child.source() == null) {
                    continue;
                }
            }
            if (!declaresSource(route, child.source())) {
                throw new TqlException(UNKNOWN_SOURCE, "View " + viewRef + ": children source "
                        + child.source() + " is not a source of the route"
                        + " (a sources: entry, or main)");
            }
        }
        return childEmbeds;
    }

    /** The same for a dashboard's {@code panels:}: the embedding ones, and the source guard. */
    private static Map<Integer, Embed> panelEmbeds(Path home, String viewRef, ViewSpec spec,
            RouteDefinition route, Function<String, RouteDefinition> postRouteByPath,
            Function<String, Path> viewById) {
        Map<Integer, Embed> panelEmbeds = new LinkedHashMap<>();
        for (int index = 0; index < spec.panels().size(); index++) {
            ViewSpec.Panel panel = spec.panels().get(index);
            if (panel.view() != null) {
                panelEmbeds.put(index, embed(home, viewRef, panel.view(), panel.source(),
                        route, postRouteByPath, viewById));
                if (panel.source() == null) {
                    continue;
                }
            }
            String panelSource = panelSource(panel);
            if (!declaresSource(route, panelSource)) {
                throw new TqlException(UNKNOWN_SOURCE, "View " + viewRef + ": panel source "
                        + panelSource + " is not a source of the route"
                        + " (a sources: entry, or main)");
            }
        }
        return panelEmbeds;
    }

    /**
     * What the view's {@code domain:} references contribute to the read side: the output
     * policies to apply before assembly, and the catalog each coded column resolves names
     * through.
     */
    private record ReadSide(
            Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> policies,
            Map<String, String> catalogs) {
    }

    /**
     * Read-side domain references (docs/view-composition.md wave 3a): an explicit
     * {@code domain:} on a column or field must name a declared domain, and the domain's
     * classification/mask become the column's output policy (wave 3b) — the same vocabulary the
     * JSON renderer applies, so one row can never render masked in JSON and raw in HTML.
     */
    private static ReadSide readSide(Path home, String viewRef, ViewSpec spec,
            Map<Integer, Embed> childEmbeds, Map<Integer, Embed> panelEmbeds) {
        Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies = new LinkedHashMap<>();
        Map<String, String> catalogByColumn = new LinkedHashMap<>();
        Map<String, String> domainByColumn = new LinkedHashMap<>();
        spec.columns().stream().filter(column -> column.domain() != null)
                .forEach(column -> domainByColumn.put(column.name(), column.domain()));
        spec.fields().stream().filter(field -> field.domain() != null)
                .forEach(field -> domainByColumn.put(field.name(), field.domain()));
        // A child's and a panel's columns carry domain: too. They were left out until now,
        // which meant a masked domain named on a detail view's history table rendered raw —
        // the applier keys on the column name and never saw it.
        spec.children().stream().flatMap(child -> child.columns().stream())
                .filter(column -> column.domain() != null)
                .forEach(column -> domainByColumn.put(column.name(), column.domain()));
        spec.panels().stream().flatMap(panel -> panel.columns().stream())
                .filter(column -> column.domain() != null)
                .forEach(column -> domainByColumn.put(column.name(), column.domain()));
        if (!domainByColumn.isEmpty()) {
            io.tesseraql.yaml.domain.FieldDomains domains = io.tesseraql.yaml.domain.FieldDomains
                    .load(home);
            domainByColumn.forEach((column, domainName) -> {
                io.tesseraql.yaml.model.InputField domain = domains.require(domainName,
                        "view " + viewRef);
                if (domain.classification() != null || domain.mask() != null) {
                    readPolicies.put(column,
                            new io.tesseraql.yaml.model.ResponseSpec.FieldPolicy(null, null,
                                    domain.mask(), domain.classification(), null));
                }
                // The read side of docs/lookups.md decision 8: a domain whose legal values are
                // a catalog's codes names, for every surface that renders that column, the
                // catalog the code's name comes from.
                if (domain.codes() != null && !domain.codes().isBlank()) {
                    catalogByColumn.put(column, domain.codes());
                }
            });
        }
        // Embedded views mask through the host render, so their policies join the host's.
        // Their catalog references stay with them: the embedded binding assembles its own
        // model and resolves its own names.
        for (Embed embedded : childEmbeds.values()) {
            readPolicies.putAll(embedded.binding().readPolicies);
        }
        for (Embed embedded : panelEmbeds.values()) {
            readPolicies.putAll(embedded.binding().readPolicies);
        }
        return new ReadSide(Map.copyOf(readPolicies), Map.copyOf(catalogByColumn));
    }

    /**
     * Resolves one embedded view (docs/view-composition.md wave 2b): the id must be in the
     * registry, the document must not itself embed (depth is 1, TQL-VIEW-3318 — the guard that
     * also makes self-embedding impossible), and its sources validate against the HOST route —
     * the route stays the sole data owner.
     */
    private static Embed embed(Path home, String hostRef, String embeddedId,
            String sourceOverride, RouteDefinition route,
            Function<String, RouteDefinition> postRouteByPath, Function<String, Path> viewById) {
        Path file = viewById.apply(embeddedId);
        if (file == null) {
            throw new TqlException(UNRESOLVED_VIEW, "View " + hostRef + ": embedded view "
                    + embeddedId + " does not resolve to a view document id");
        }
        ViewSpec embedded = ViewSpec.parse(file);
        boolean embedsFurther = embedded.children().stream().anyMatch(c -> c.view() != null)
                || embedded.panels().stream().anyMatch(p -> p.view() != null);
        if (embedsFurther) {
            throw new TqlException(EMBED_DEPTH, "View " + hostRef + ": embedded view "
                    + embeddedId + " embeds views itself — embedding depth is 1");
        }
        return new Embed(of(home, embeddedId, route, postRouteByPath, viewById),
                sourceOverride);
    }

    /**
     * Validates slot names against the view kind's offering (L1) and resolves each fragment
     * reference ({@code <template> :: <fragment>}) into the engine-relative form the pattern
     * inserts via preprocessing. The template resolves against the view document's own
     * directory first, then {@code templates/} — never the referencing route's directory, so
     * a shared view resolves identically everywhere (docs/view-composition.md wave 1).
     */
    private static Map<String, String> resolveSlots(Path appHome, Path viewDir, ViewSpec spec) {
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
            String engineName = TemplateResolution.resolve(appHome, viewDir, template);
            resolved.put(name, engineName + " :: " + fragment);
        });
        return Map.copyOf(resolved);
    }

    /**
     * The host entry's {@code source:} override remapped onto the embedded document's own
     * source key — the embedded model reads its declared source and finds the host's data.
     */
    private static Map<String, Object> embedContext(Map<String, Object> context, Embed embed) {
        if (embed.sourceOverride() == null) {
            return context;
        }
        Map<String, Object> remapped = new LinkedHashMap<>(context);
        remapped.put(embed.binding().spec.source(), context.get(embed.sourceOverride()));
        return remapped;
    }

    /**
     * A child/panel {@code source:} must be {@code main} or one of the route's other
     * {@code sources:} entries (TQL-VIEW-3308) — every source publishes the {@code {rows}}
     * shape the model assembly reads.
     */
    private static boolean declaresSource(RouteDefinition route, String source) {
        return io.tesseraql.yaml.model.RouteDefinition.MAIN.equals(source)
                || (route != null && route.sources().containsKey(source));
    }

    /** The template name the renderer feeds to the engine (pattern or per-view retarget). */
    public String entryTemplate() {
        return entryTemplate;
    }

    public ViewSpec spec() {
        return spec;
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
     * The live-view attribute set (docs/realtime.md): with {@code refreshOn:}, the view's
     * refresh region carries the htmx sse wiring — connect to {@code /_tesseraql/topics} for
     * the one topic, and on that named event re-GET the page itself, selecting the same region
     * back out of it (for a list, the table region the search box already refreshes; for a
     * detail or dashboard, the {@code <id>-view} region). The stream carries no data; the
     * refetch is the ordinary, fully-authorized route. Empty strings drop the attributes
     * entirely via {@code th:attr}.
     */
    private void live(Map<String, Object> v, String pagePath, String region,
            boolean includeHiddenInputs) {
        String topic = spec.refreshOn() == null ? "" : spec.refreshOn().trim();
        boolean on = !topic.isEmpty();
        v.put("liveExt", on ? "sse" : "");
        v.put("liveConnect", on
                ? "/_tesseraql/events?topics=" + java.net.URLEncoder.encode(topic,
                        java.nio.charset.StandardCharsets.UTF_8)
                : "");
        v.put("liveGet", on ? pagePath : "");
        v.put("liveTrigger", on ? "sse:" + topic : "");
        v.put("liveSelect", on ? "#" + region : "");
        // The refetch reads live DOM state, not the render-time URL: the hidden sort/dir
        // inputs and the typed search term are the current truth (the search box swaps the
        // region without navigating, so the URL can be stale). The search input sits outside
        // the swapped region, so a live refresh never clobbers in-progress typing.
        String include = !on || !includeHiddenInputs
                ? ""
                : spec.search() == null
                        ? "#" + region + " input[type='hidden']"
                        : "#" + region + " input[type='hidden'], #" + spec.id() + "-search";
        v.put("liveInclude", include);
        v.put("liveTarget", on ? "this" : "");
        v.put("liveSwap", on ? "outerHTML" : "");
    }

    /** {@code pagePath} is the request path sort/search links resolve against. */
    public Map<String, Object> model(Map<String, Object> context, Locale locale,
            String pagePath) {
        return model(context, locale, pagePath, policyId -> true);
    }

    /**
     * The per-principal variant (docs/view-composition.md wave 4): {@code permits} evaluates a
     * field's write {@code policy:} for the current principal — a failing field is omitted
     * from the rendered form, the same declaration the request binder enforces. The
     * permissive default keeps build-time render paths (ejection previews, tests) whole.
     */
    public Map<String, Object> model(Map<String, Object> context, Locale locale,
            String pagePath, java.util.function.Predicate<String> permits) {
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
            formModel(v, catalog, locale, context, data, permits);
        } else if (ViewSpec.DETAIL.equals(spec.view())) {
            detailModel(v, catalog, locale, context, data, pagePath, permits);
        } else if (ViewSpec.DASHBOARD.equals(spec.view())) {
            dashboardModel(v, catalog, locale, context, pagePath, permits);
        } else {
            listModel(v, catalog, locale, context, data, pagePath);
        }
        return v;
    }

    /**
     * A form's model: the resolved action, the prefill row, and the derived fields this
     * principal may write.
     */
    private void formModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, Map<String, Object> data,
            java.util.function.Predicate<String> permits) {
        // A per-record action (/items/{id}/update) resolves its placeholders against the
        // request's path and coerced params, so one form view serves every record.
        v.put("action", interpolateAction(spec.action(), context));
        v.put("formId", spec.id().replace('.', '-') + "-form");
        Map<String, Object> row = firstRow(data);
        v.put("row", row);
        v.put("notFound", context.containsKey(spec.source()) && rows(data).isEmpty());
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (ViewFields.FieldDef field : fields) {
            // A field whose write policy: the principal fails never renders (wave 4) —
            // hiding it is derived from the same declaration the binder enforces.
            if (field.policy() != null && !permits.test(field.policy())) {
                continue;
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", field.name());
            f.put("label", message(catalog, locale, field.labelKey(), field.labelFallback()));
            f.put("widget", field.widget());
            f.put("required", field.required());
            f.put("maxLength", field.maxLength());
            f.put("min", field.min());
            f.put("max", field.max());
            f.put("options", options(field, context));
            f.put("step", field.step());
            Object value = field.valueFrom(row);
            f.put("value", value == null ? "" : String.valueOf(value));
            rendered.add(f);
        }
        v.put("fields", rendered);
    }

    /** A detail's model: the record's labelled values, then its children (tables or embeds). */
    private void detailModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, Map<String, Object> data, String pagePath,
            java.util.function.Predicate<String> permits) {
        Map<String, Object> row = firstRow(data);
        v.put("row", row);
        v.put("notFound", context.containsKey(spec.source()) && rows(data).isEmpty());
        v.put("fields", detailFields(catalog, locale, context, row));
        List<Map<String, Object>> children = new ArrayList<>();
        for (int index = 0; index < spec.children().size(); index++) {
            ViewSpec.Child child = spec.children().get(index);
            Map<String, Object> c = new LinkedHashMap<>();
            Embed embedded = childEmbeds.get(index);
            if (embedded != null) {
                c.put("embed", embedded.binding().model(
                        embedContext(context, embedded), locale, pagePath, permits));
                c.put("embedTemplate", embedded.binding().entryTemplate());
                children.add(c);
                continue;
            }
            List<Map<String, Object>> childRows = rows(sourceOf(context, child.source()));
            List<ViewSpec.Column> columns = columnsOf(child.columns(), childRows);
            c.put("title", message(catalog, locale,
                    child.title() != null
                            ? child.title()
                            : "view." + spec.id() + "." + child.source(),
                    child.title() != null ? child.title() : humanize(child.source())));
            c.put("columns", renderedColumns(catalog, locale, columns));
            c.put("rows", cellMatrix(context, columns, childRows, null, null));
            children.add(c);
        }
        v.put("children", children);
        live(v, pagePath, spec.id() + "-view", false);
    }

    /** A dashboard's model: one entry per declared panel, in authored order. */
    private void dashboardModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, String pagePath,
            java.util.function.Predicate<String> permits) {
        List<Map<String, Object>> panels = new ArrayList<>();
        for (int index = 0; index < spec.panels().size(); index++) {
            panels.add(panelModel(index, spec.panels().get(index), catalog, locale, context,
                    pagePath, permits));
        }
        v.put("panels", panels);
        // The chart scripts (the Plot bundle + the installChart module) load only where a
        // chart panel renders — pages without charts ship not a byte of charting.
        v.put("hasChart", spec.panels().stream()
                .anyMatch(panel -> "chart".equals(panel.type())));
        live(v, pagePath, spec.id() + "-view", false);
    }

    /** One panel: the head every type carries (index, type, title), then its own shape. */
    private Map<String, Object> panelModel(int index, ViewSpec.Panel panel,
            MessageCatalog catalog, Locale locale, Map<String, Object> context, String pagePath,
            java.util.function.Predicate<String> permits) {
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
            case "stat" -> statPanel(m, panel, rows);
            case "sparkline" -> sparklinePanel(m, panel, rows);
            case "chart" -> chartPanel(m, panel, catalog, locale, rows);
            case "table" -> tablePanel(m, panel, catalog, locale, context, rows);
            case "view" -> embedPanel(m, index, context, locale, pagePath, permits);
            default -> throw new IllegalStateException(panel.type());
        }
        return m;
    }

    /** A stat panel: the column's value on the first row, an em dash when there is none. */
    private static void statPanel(Map<String, Object> m, ViewSpec.Panel panel,
            List<Map<String, Object>> rows) {
        Object value = rows.isEmpty() ? null : rows.get(0).get(panel.column());
        m.put("value", value == null ? "\u2014" : String.valueOf(value));
    }

    /** A sparkline panel: the column's numeric series and the scale it draws against. */
    private static void sparklinePanel(Map<String, Object> m, ViewSpec.Panel panel,
            List<Map<String, Object>> rows) {
        List<Double> values = numbers(rows, panel.column());
        // Not "values": OGNL resolves map.values to Map#values(), not the key.
        m.put("series", values.stream().map(ViewBinding::plain)
                .reduce((a, c) -> a + "," + c).orElse(""));
        m.put("min", "0");
        m.put("max", plain(values.stream().mapToDouble(Double::doubleValue)
                .max().orElse(1)));
    }

    /**
     * A chart panel, following the kit's data-hc-chart recipe (docs/analytics-experience.md
     * track 2): the server emits the source table — the data, the no-JS fallback, and the
     * screen-reader representation in one — and the kit's installChart draws the Observable
     * Plot SVG client-side. Column one is x; every series column follows, marked per column
     * under kind: combo.
     */
    private void chartPanel(Map<String, Object> m, ViewSpec.Panel panel, MessageCatalog catalog,
            Locale locale, List<Map<String, Object>> rows) {
        List<ViewSpec.Series> series = panel.effectiveSeries();
        m.put("chartKind", panel.kind() == null ? "bar" : panel.kind());
        m.put("xType", panel.xType());
        m.put("height", panel.height());
        m.put("legend", panel.legend() == null
                ? null
                : String.valueOf(panel.legend()));
        m.put("yLabel", panel.yLabel());
        m.put("xLabel", message(catalog, locale,
                "view." + spec.id() + "." + panel.x(), humanize(panel.x())));
        List<Map<String, Object>> seriesHeads = new ArrayList<>();
        for (ViewSpec.Series charted : series) {
            Map<String, Object> head = new LinkedHashMap<>();
            head.put("label", message(catalog, locale,
                    charted.label() != null
                            ? charted.label()
                            : "view." + spec.id() + "." + charted.column(),
                    charted.label() != null
                            ? charted.label()
                            : humanize(charted.column())));
            head.put("mark", charted.mark());
            seriesHeads.add(head);
        }
        m.put("series", seriesHeads);
        List<List<String>> chartRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>(series.size() + 1);
            Object x = row.get(panel.x());
            cells.add(x == null ? "" : String.valueOf(x));
            for (ViewSpec.Series charted : series) {
                Object value = row.get(charted.column());
                cells.add(value == null ? "" : String.valueOf(value));
            }
            chartRows.add(cells);
        }
        m.put("chartRows", chartRows);
    }

    /** A table panel: the same column/cell assembly a list view and a detail child use. */
    private void tablePanel(Map<String, Object> m, ViewSpec.Panel panel, MessageCatalog catalog,
            Locale locale, Map<String, Object> context, List<Map<String, Object>> rows) {
        List<ViewSpec.Column> columns = columnsOf(panel.columns(), rows);
        m.put("columns", renderedColumns(catalog, locale, columns));
        m.put("rows", cellMatrix(context, columns, rows, null, null));
    }

    /**
     * An embedded view (docs/view-composition.md wave 2b): the sub-model rides the panel; the
     * pattern inserts the embedded document's own entry fragment, which brings its own card.
     */
    private void embedPanel(Map<String, Object> m, int index, Map<String, Object> context,
            Locale locale, String pagePath, java.util.function.Predicate<String> permits) {
        Embed embedded = panelEmbeds.get(index);
        m.put("embed", embedded.binding().model(
                embedContext(context, embedded), locale, pagePath, permits));
        m.put("embedTemplate", embedded.binding().entryTemplate());
    }

    /** A list's model: the pager, the sort/search state, and the column/cell matrix. */
    private void listModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, Map<String, Object> data, String pagePath) {
        List<Map<String, Object>> rows = rows(data);
        List<ViewSpec.Column> columns = columnsOf(spec.columns(), rows);
        Map<String, Object> params = params(context);
        v.put("path", pagePath);
        live(v, pagePath, spec.id() + "-table", true);
        Map<String, Object> page = pager(context, params, pagePath);
        if (page != null && page.get("next") == null && page.get("totalRows") != null) {
            // A counted offset page knows its absolute window — the grid page's status line
            // reads it (docs/list-surface.md decision 1). A keyset page has no absolute
            // position, and an uncounted one no total; both keep the plain page number.
            long number = page.get("number") instanceof Number n ? n.longValue() : 1;
            long size = page.get("size") instanceof Number n ? n.longValue() : rows.size();
            long from = rows.isEmpty() ? 0 : (number - 1) * size + 1;
            long to = (number - 1) * size + rows.size();
            page.put("from", from);
            page.put("to", to);
            page.put("range", message(catalog, locale, "tql.view.range",
                    "{from}–{to} of {total}")
                    .replace("{from}", String.valueOf(from))
                    .replace("{to}", String.valueOf(to))
                    .replace("{total}", String.valueOf(page.get("totalRows"))));
        }
        v.put("page", page);
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
        filterModel(v, catalog, locale, context, params, pagePath);
        List<Map<String, Object>> rendered = renderedColumns(catalog, locale, columns);
        // The header contract every sortable grid shares, studio tables included.
        io.tesseraql.yaml.view.SortState state = io.tesseraql.yaml.view.SortState.of(sort, dir,
                columns.stream().filter(ViewSpec.Column::isSortable).map(ViewSpec.Column::name)
                        .toList(),
                null, false);
        for (int i = 0; i < columns.size(); i++) {
            ViewSpec.Column column = columns.get(i);
            if (!column.isSortable()) {
                continue;
            }
            Map<String, Object> c = rendered.get(i);
            c.put("sortable", true);
            c.put("ariaSort", state.ariaSort(column.name()));
            c.put("sortHref", state.href(pagePath, column.name(), null));
        }
        v.put("columns", rendered);
        List<String> tokens = rowTokens(rows);
        if (tokens != null) {
            // The row's machine identity (docs/list-surface.md decision 2): the anchor the
            // table pattern renders, the fragment a `location: back` redirect refocuses.
            v.put("anchors", tokens.stream().map(token -> "row-" + token).toList());
        }
        v.put("rows", cellMatrix(context, columns, rows, tokens, returnBase(page, params,
                pagePath)));
    }

    /**
     * One opaque token per row over the declared {@code key:} (docs/list-surface.md decision
     * 2), or null when the list declares none. A row missing a key component is a refusal
     * (TQL-VIEW-3322), never a silent skip.
     */
    private List<String> rowTokens(List<Map<String, Object>> rows) {
        if (spec.key().isEmpty()) {
            return null;
        }
        List<String> tokens = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            try {
                tokens.add(io.tesseraql.core.rows.RowTokens.encode(row, spec.key()));
            } catch (IllegalArgumentException ex) {
                throw new TqlException(INVALID_ROW_KEY, "View " + spec.id() + ": "
                        + ex.getMessage() + " (declared key: " + spec.key() + ")");
            }
        }
        return tokens;
    }

    /**
     * The list's own current URL, canonically rebuilt from the state the pager carries — what
     * a page-frame row link sends along as {@code _return} so a {@code location: back}
     * redirect lands on the same conditions, sort and page (docs/list-surface.md decision 11).
     * Null outside the page frame.
     */
    private String returnBase(Map<String, Object> page, Map<String, Object> params,
            String pagePath) {
        if (!ViewSpec.LAYOUT_PAGE.equals(spec.effectiveLayout()) || pagePath.isEmpty()) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        if (page != null && page.get("number") instanceof Number n && n.longValue() > 1) {
            query.append("&page=").append(n.longValue());
        }
        query.append(state(params));
        return query.isEmpty() ? pagePath : pagePath + "?" + query.substring(1);
    }

    /** A panel's context source: its {@code source:} or the document's {@code main} result. */
    private static String panelSource(ViewSpec.Panel panel) {
        return panel.source() == null || panel.source().isBlank()
                ? io.tesseraql.yaml.model.RouteDefinition.MAIN
                : panel.source();
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
        String state = state(params);
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

    /**
     * The query-state fragment ({@code &k=v…}) every self-referencing list URL carries:
     * sort/dir, the chosen size, the search term, and every applied filter
     * (docs/list-surface.md decision 6).
     */
    private String state(Map<String, Object> params) {
        return chromeState(params) + filterState(params, null);
    }

    /**
     * The grid page's filter model (docs/list-surface.md decision 6): the dialog fields with
     * their current values, and the applied-condition chips whose remove links are real URLs
     * minus that one condition — dropping a filter works without JavaScript.
     */
    private void filterModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, Map<String, Object> params, String pagePath) {
        if (filterFields.isEmpty()) {
            return;
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        List<Map<String, Object>> chips = new ArrayList<>();
        String removePattern = message(catalog, locale, "tql.view.removeFilter",
                "Remove {label}");
        for (ViewFields.FieldDef def : filterFields) {
            String value = str(params.get(def.name()));
            String label = message(catalog, locale, def.labelKey(), def.labelFallback());
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", def.name());
            f.put("label", label);
            f.put("widget", def.widget());
            f.put("required", false);
            f.put("maxLength", def.maxLength());
            f.put("min", def.min());
            f.put("max", def.max());
            f.put("step", def.step());
            f.put("value", value);
            List<Map<String, Object>> options = List.of();
            if ("select".equals(def.widget())) {
                // The first, empty option is the "any" choice a filter needs and a form
                // field does not: an empty submit simply applies no condition.
                options = new ArrayList<>();
                Map<String, Object> any = new LinkedHashMap<>();
                any.put("value", "");
                any.put("label", "");
                options.add(any);
                options.addAll(options(def, context));
                f.put("options", options);
            }
            fields.add(f);
            if (value.isEmpty()) {
                continue;
            }
            Map<String, Object> chip = new LinkedHashMap<>();
            chip.put("label", label);
            chip.put("value", optionLabel(options, value));
            chip.put("removeHref", href(pagePath,
                    chromeState(params) + filterState(params, def.name())));
            chip.put("removeLabel", removePattern.replace("{label}", label));
            chips.add(chip);
        }
        v.put("filterFields", fields);
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("chips", chips);
        bar.put("clearHref", href(pagePath, chromeState(params)));
        v.put("filterBar", bar);
    }

    /** The display text a chip shows: the matching option's label, else the raw value. */
    private static String optionLabel(List<Map<String, Object>> options, String value) {
        for (Map<String, Object> option : options) {
            if (value.equals(option.get("value"))) {
                return str(option.get("label"));
            }
        }
        return value;
    }

    /** The non-filter state: sort/dir, size, and the search term. */
    private String chromeState(Map<String, Object> params) {
        StringBuilder state = new StringBuilder();
        for (String key : List.of("sort", "dir", "size")) {
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
        return state.toString();
    }

    /** Every applied filter as query state, minus the one being removed (a chip's × link). */
    private String filterState(Map<String, Object> params, String excluded) {
        StringBuilder state = new StringBuilder();
        for (ViewSpec.Filter filter : spec.filters()) {
            if (filter.name().equals(excluded)) {
                continue;
            }
            String value = str(params.get(filter.name()));
            if (!value.isEmpty()) {
                state.append('&').append(filter.name()).append('=').append(encode(value));
            }
        }
        return state.toString();
    }

    /** {@code pagePath} plus a {@code &k=v…} state fragment as a navigable href. */
    private static String href(String pagePath, String state) {
        return state.isEmpty() ? pagePath : pagePath + "?" + state.substring(1);
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
        return Interpolation.interpolateString(action, new EvaluationContext(merged));
    }

    /** A detail view's labelled values: explicit {@code fields:}, else the row's own columns. */
    private List<Map<String, Object>> detailFields(MessageCatalog catalog, Locale locale,
            Map<String, Object> context, Map<String, Object> row) {
        List<Map<String, Object>> rendered = new ArrayList<>();
        List<ViewSpec.Field> selection = spec.fields();
        if (selection.isEmpty()) {
            selection = new ArrayList<>();
            for (String name : row.keySet()) {
                selection.add(new ViewSpec.Field(name, null, null, null, null));
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
            f.put("value", resolved(context, field.name(), row.get(field.name())));
            rendered.add(f);
        }
        return rendered;
    }

    /**
     * A cell's text: the name behind a code where the column's {@code domain:} names a catalog
     * (docs/lookups.md, decision 8), and the value itself everywhere else.
     *
     * <p>Two fallbacks beyond the catalog's own. A catalog with no store bound renders the code,
     * so a screen degrades to what the database holds rather than to nothing. And because the
     * output policies have already been applied to this context, a masked value simply misses
     * the catalog and stays masked: resolution can never un-mask a column.
     */
    private String resolved(Map<String, Object> context, String column, Object value) {
        if (value == null) {
            return "";
        }
        String catalogName = catalogByColumn.get(column);
        io.tesseraql.core.catalog.CodeCatalog codes = catalogName == null
                ? null
                : catalog(context, catalogName);
        return codes == null ? String.valueOf(value) : codes.of(value);
    }

    /** One catalog out of the {@code codes} object the {@link CatalogBinder} publishes. */
    private static io.tesseraql.core.catalog.CodeCatalog catalog(Map<String, Object> context,
            String name) {
        Object catalogs = context.get(io.tesseraql.pipeline.TesseraqlProperties.CODES);
        Object catalog = catalogs instanceof Map<?, ?> map ? map.get(name) : null;
        return catalog instanceof io.tesseraql.core.catalog.CodeCatalog codes ? codes : null;
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

    /**
     * The cell matrix: text per column per row, links resolved against the row's values. On the
     * page frame ({@code returnBase} non-null), an app-local link also carries the list's own
     * URL as {@code _return} — with the acting row's fragment when a key is declared — so a
     * {@code location: back} redirect lands back on these conditions, focused on this row
     * (docs/list-surface.md decision 11).
     */
    private List<List<Map<String, Object>>> cellMatrix(Map<String, Object> context,
            List<ViewSpec.Column> columns, List<Map<String, Object>> rows, List<String> tokens,
            String returnBase) {
        List<List<Map<String, Object>>> cells = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            EvaluationContext rowContext = new EvaluationContext(row);
            String returnTo = returnBase == null
                    ? null
                    : tokens == null
                            ? returnBase
                            : returnBase + "#row-" + tokens.get(index);
            List<Map<String, Object>> line = new ArrayList<>();
            for (ViewSpec.Column column : columns) {
                Map<String, Object> cell = new LinkedHashMap<>();
                Object value = row.get(column.name());
                cell.put("text", column.text() != null
                        ? column.text()
                        : resolved(context, column.name(), value));
                cell.put("button", column.text() != null);
                String href = column.link() == null
                        ? null
                        : Interpolation.interpolateUrl(column.link(), rowContext);
                if (href != null && returnTo != null && href.startsWith("/")) {
                    href += (href.indexOf('?') >= 0 ? '&' : '?') + "_return=" + encode(returnTo);
                }
                cell.put("href", href);
                line.add(cell);
            }
            cells.add(line);
        }
        return cells;
    }

    /** A context entry carrying a {@code {rows, rowCount}} result ({@code main} or a named source). */
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

    /**
     * A field's options as {@code {value, label}} pairs (docs/lookups.md, decision 9).
     *
     * <p>One shape whatever the source: an {@code enum:} value is its own label, and a
     * {@code codes:} field takes the catalog's <em>active</em> entries in the order the catalog
     * declares — so a form offers what may still be chosen while a retired code keeps rendering
     * on the rows that already carry it.
     */
    private static List<Map<String, Object>> options(ViewFields.FieldDef field,
            Map<String, Object> context) {
        List<Map<String, Object>> options = new ArrayList<>();
        if (field.codes() != null && !field.codes().isBlank()) {
            io.tesseraql.core.catalog.CodeCatalog codes = catalog(context, field.codes());
            if (codes != null) {
                codes.options().forEach(entry -> {
                    Map<String, Object> option = new LinkedHashMap<>();
                    option.put("value", String.valueOf(entry.key()));
                    option.put("label", entry.label());
                    options.add(option);
                });
            }
            return options;
        }
        field.options().forEach(value -> {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("value", value);
            option.put("label", value);
            options.add(option);
        });
        return options;
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
            derived.add(new ViewSpec.Column(name, null, null, null, null, null));
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
