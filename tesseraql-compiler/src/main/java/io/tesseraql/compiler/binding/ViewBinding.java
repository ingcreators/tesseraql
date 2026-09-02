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
    /** TQL-VIEW-3330: the row a locked form renders from carries no value for its lock column. */
    static final TqlErrorCode MISSING_LOCK = new TqlErrorCode(TqlDomain.VIEW, 3330);

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
    private final io.tesseraql.yaml.model.PageSpec pagination;
    // The workflow a detail view declares (docs/workflow-surface.md), handed in by the
    // compiler after resolution — the fluent-setter shape HtmlResponseRenderer.basePath uses,
    // because the definition lives in the manifest the of(...) factory deliberately never sees.
    private io.tesseraql.yaml.model.WorkflowDefinition workflow;
    private String workflowBasePath;
    /** The upload the import view's {@code action:} names, resolved at build time. */
    private final ImportTarget importTarget;
    /**
     * The lock column the form's {@code action:} route declares, resolved at build time like
     * {@link #importTarget} — the value itself is per render, and comes from the row.
     */
    private final String lockColumn;

    /**
     * What an import view renders around its report: the address it uploads to, the file types
     * that address accepts, and the columns it expects.
     *
     * @param accept the file input's {@code accept} list, or null when the format's codec is not
     *               on this classpath — a hosted application's optional codec is discovered by
     *               its own module loader at runtime, and refusing to compile the page over an
     *               attribute that only narrows a file picker would be the wrong trade
     */
    record ImportTarget(String action, String accept, List<String> columns) {
    }

    private ViewBinding(ViewSpec spec, String entryTemplate, List<ViewFields.FieldDef> fields,
            Map<String, String> slots, Path appHome, Map<Integer, Embed> childEmbeds,
            Map<Integer, Embed> panelEmbeds,
            Map<String, io.tesseraql.yaml.model.ResponseSpec.FieldPolicy> readPolicies,
            Map<String, String> catalogByColumn, List<ViewFields.FieldDef> filterFields,
            io.tesseraql.yaml.model.PageSpec pagination, ImportTarget importTarget,
            String lockColumn) {
        this.importTarget = importTarget;
        this.lockColumn = lockColumn;
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
        this.pagination = pagination;
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

    /** The view's declared {@code workflow:} id, or null — the compiler resolves it. */
    public String workflowRef() {
        return spec.workflow();
    }

    /** The view's data source key — the result set the workflow facts step evaluates. */
    public String sourceKey() {
        return spec.source();
    }

    /** Hands over the resolved workflow and its base path (docs/workflow-surface.md). */
    public void workflow(io.tesseraql.yaml.model.WorkflowDefinition definition,
            String basePath) {
        this.workflow = definition;
        this.workflowBasePath = basePath;
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
        String entry = spec.template() != null
                ? TemplateResolution.resolve(home, viewDir, spec.template())
                : "tql/view/" + spec.view();
        // The grid page's filter fields derive from the declaring route's own input: block
        // (docs/list-surface.md decision 6) — the same declaration the request binder coerces.
        List<ViewFields.FieldDef> filterFields = spec.filters().isEmpty()
                ? List.of()
                : ViewFields.deriveFilters(viewRef, spec,
                        route == null ? null : route.input());
        String lock = ViewSpec.FORM.equals(spec.view())
                ? lockColumn(spec, postRouteByPath)
                : null;
        if (lock != null && readSide.policies().containsKey(lock)) {
            // A masked value is present and non-null, so it would sail past every render check
            // into a form whose save can never match — "This record changed", for a masking
            // decision. Refuse the combination instead (docs/edit-conflict.md decision 3).
            throw new TqlException(MISSING_LOCK, "View " + viewRef + ": the action route's lock"
                    + " column '" + lock + "' carries a read policy — a masked or hidden lock"
                    + " cannot be sent back unchanged, so the form could never be saved");
        }
        return new ViewBinding(spec, entry, fields, resolveSlots(home, viewDir, spec), home,
                Map.copyOf(childEmbeds), Map.copyOf(panelEmbeds), readSide.policies(),
                readSide.catalogs(), filterFields,
                route == null ? null : route.pagination(),
                ViewSpec.IMPORT.equals(spec.view())
                        ? importTarget(viewRef, spec, postRouteByPath)
                        : null,
                lock);
    }

    /**
     * The import view's upload target, read off the {@code action:} route's own declaration —
     * the same reason a form derives its fields from its action route rather than restating
     * them. A page that named its accepted types or its expected columns itself would be a
     * second copy of the import, free to disagree with the one the parse enforces.
     */
    private static ImportTarget importTarget(String viewRef, ViewSpec spec,
            Function<String, RouteDefinition> postRouteByPath) {
        RouteDefinition action = postRouteByPath.apply(spec.action());
        if (action == null) {
            throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action "
                    + spec.action() + " matches no POST route");
        }
        io.tesseraql.yaml.model.ImportSpec importSpec = action.fileImport();
        if (importSpec == null) {
            throw new TqlException(UNKNOWN_ACTION, "View " + viewRef + ": action route "
                    + action.id() + " is not a file-import route — an import view renders one"
                    + " import's upload, report and confirm");
        }
        io.tesseraql.core.files.FileCodecs codecs = io.tesseraql.core.files.FileCodecs.discover();
        String format = importSpec.format();
        String accept = null;
        if (format != null && codecs.supports(format)) {
            io.tesseraql.core.files.FileCodec codec = codecs.require(format);
            // The media type alone: a codec's contentType() is a response header value, so it
            // carries the charset a download needs and an `accept` list must not have — file
            // pickers match on the type, and `text/csv; charset=utf-8` matches nothing.
            String media = codec.contentType();
            int parameters = media.indexOf(';');
            accept = codec.extension() + ","
                    + (parameters < 0 ? media : media.substring(0, parameters)).trim();
        }
        return new ImportTarget(spec.action(), accept,
                importSpec.columns().stream()
                        .map(io.tesseraql.yaml.model.ColumnSpec::name).toList());
    }

    /**
     * The lock column the form's {@code action:} route declares (docs/edit-conflict.md decision
     * 1), read off that route rather than restated on the page — the same reason a form derives
     * its fields from its action rather than listing them, and the same move
     * {@link #importTarget} makes for an import.
     *
     * <p>No refusal for a null action: {@link #formFields} resolved the same action a moment
     * earlier and threw {@code TQL-VIEW-3303}, so a null here is unreachable rather than
     * tolerated.
     */
    private static String lockColumn(ViewSpec spec,
            Function<String, RouteDefinition> postRouteByPath) {
        RouteDefinition action = postRouteByPath.apply(spec.action());
        return action == null || action.lock() == null ? null : action.lock().column();
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
        } else if (ViewSpec.IMPORT.equals(spec.view())) {
            importModel(v, catalog, locale, context);
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
     * The reviewed upload's model (docs/csv-import.md decision 7). Two states, one document:
     * before an upload it is the kit's file-upload form alone, and after one it is that form
     * plus the report the parse answered and — exactly when a committable set exists — the
     * confirm form that spends the token.
     *
     * <p>The report and the token arrive through the context because they belong to the
     * request that parsed the file: the GET that renders the empty page has neither, and
     * nothing here fabricates them.
     */
    private void importModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context) {
        v.put("formId", spec.id() + "-upload");
        v.put("action", importTarget.action());
        v.put("accept", importTarget.accept());
        v.put("columns", importTarget.columns().isEmpty() ? null : importTarget.columns());
        v.put("uploadLabel", message(catalog, locale, "tql.import.file", "File"));
        v.put("uploadSubmit", message(catalog, locale, "tql.import.upload", "Check file"));
        v.put("confirmSubmit", message(catalog, locale, "tql.import.commit", "Import"));
        if (!(context.get(ImportContext.KEY) instanceof ImportContext outcome)) {
            return;
        }
        v.put("report", ImportReports
                .of(spec.id() + "-import", outcome.review(), outcome.locate(), catalog, locale)
                .render(catalog, locale).model());
        // The confirm form exists exactly when a committable set does (decision 3): the token
        // is the same fact the status code was read off, so the button and the 200 can never
        // disagree about whether there is anything to import.
        if (!outcome.review().committable()) {
            return;
        }
        v.put("token", outcome.review().batchId());
        v.put("confirmAction", outcome.commitUrl());
        v.put("expires", outcome.review().expiresAt() == null
                ? null
                : ViewMessages.text(catalog, locale, "tql.import.expires",
                        "This check expires at {at}.",
                        Map.of("at", String.valueOf(outcome.review().expiresAt()))));
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
        // The declared lock (docs/edit-conflict.md decision 3), looked up in the row this form
        // was rendered from. Deliberately not guarded on the value: a field that vanished when
        // the read forgot to project the column would leave the save silently unlocked, which is
        // the regression this surface exists to refuse. The gate is the row's emptiness, which is
        // exactly and only "this render has no record" — a create form, or a not-found edit page
        // that emits no form at all.
        if (lockColumn != null && !row.isEmpty()) {
            v.put("lock", String.valueOf(lockValue(row)));
        }
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
            // A lookup field's render extras (docs/reference-lookup.md decision 2): the code
            // input's name and resolve URL, plus the initial state — one shape with the
            // synthesized resolve route's re-render, so the form's first paint and every
            // swap are the same fragment contract.
            if ("lookup".equals(field.widget()) && field.lookup() != null) {
                f.put("lookup", LookupFieldModel.initial(field,
                        String.valueOf(v.get("action")) + "/_lookup/" + field.name(),
                        String.valueOf(f.get("value"))));
            }
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
        if (workflow != null) {
            v.put("workflow", workflowModel(catalog, locale, context, pagePath));
        }
        live(v, pagePath, spec.id() + "-view", false);
    }

    /**
     * The transitions region and stepper display model (docs/workflow-surface.md), built from
     * the facts the {@link WorkflowViewBinder} published: the stepper walks the states in
     * declaration order — the only order the model has — marking the current one and, by
     * position, the walked path; each legal transition becomes a button posting its own
     * synthesized route, disabled-with-reason when its guard said no, and confirm-gated when
     * it enters a terminal state. Absent facts (no row) render nothing.
     */
    private Map<String, Object> workflowModel(MessageCatalog catalog, Locale locale,
            Map<String, Object> context, String pagePath) {
        Object raw = context.get("workflow");
        if (!(raw instanceof Map<?, ?> factsRaw)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = (Map<String, Object>) factsRaw;
        String current = str(facts.get("state"));
        Map<String, Object> model = new LinkedHashMap<>();
        List<Map<String, Object>> states = new ArrayList<>();
        int currentIndex = -1;
        for (int i = 0; i < workflow.states().size(); i++) {
            if (workflow.states().get(i).id().equals(current)) {
                currentIndex = i;
            }
        }
        for (int i = 0; i < workflow.states().size(); i++) {
            io.tesseraql.yaml.model.StateSpec state = workflow.states().get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", message(catalog, locale,
                    "workflow." + workflow.id() + ".state." + state.id(),
                    humanize(state.id())));
            entry.put("current", i == currentIndex);
            entry.put("complete", currentIndex >= 0 && i < currentIndex);
            states.add(entry);
        }
        model.put("states", states);
        String docId = str(facts.get("docId"));
        List<Map<String, Object>> transitions = new ArrayList<>();
        Object list = facts.get("transitions");
        boolean blocked = false;
        if (list instanceof List<?> entries) {
            for (Object entryRaw : entries) {
                if (!(entryRaw instanceof Map<?, ?> factRaw)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fact = (Map<String, Object>) factRaw;
                String id = str(fact.get("id"));
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("id", id);
                t.put("label", message(catalog, locale,
                        "workflow." + workflow.id() + ".transition." + id, humanize(id)));
                boolean enabled = Boolean.TRUE.equals(fact.get("enabled"));
                t.put("enabled", enabled);
                blocked |= Boolean.TRUE.equals(fact.get("blocked"));
                if (!enabled && fact.get("reason") != null) {
                    t.put("reason", str(fact.get("reason")));
                } else if (!enabled && !Boolean.TRUE.equals(fact.get("blocked"))) {
                    t.put("reason", message(catalog, locale, "tql.workflow.notNow",
                            "Not available in this state."));
                }
                t.put("action", workflowBasePath + "/" + encode(docId) + "/" + id);
                if (Boolean.TRUE.equals(fact.get("terminal"))) {
                    t.put("confirm", message(catalog, locale, "tql.workflow.confirm",
                            "This finishes the document's lifecycle. Continue?"));
                }
                if (fact.get("joinTotal") != null) {
                    t.put("progress", message(catalog, locale, "tql.workflow.stamped",
                            "{done} of {total} stamped")
                            .replace("{done}", String.valueOf(fact.get("joinDone")))
                            .replace("{total}", String.valueOf(fact.get("joinTotal"))));
                }
                transitions.add(t);
            }
        }
        model.put("transitions", transitions);
        // The comment field renders when any offered transition demands one
        // (docs/workflow-surface.md decision 5, upfront rather than only-on-refusal — a
        // recorded deviation): one textarea, shared by the form, the pressed button's verb
        // decides which transition it rides. The hint names the demanding transitions; it
        // is never HTML-required, because that would block the transitions that don't.
        List<String> demanding = new ArrayList<>();
        if (list instanceof List<?> entries) {
            for (Object entryRaw : entries) {
                if (entryRaw instanceof Map<?, ?> fact
                        && Boolean.TRUE.equals(fact.get("commentRequired"))
                        && Boolean.TRUE.equals(fact.get("enabled"))) {
                    String id = str(fact.get("id"));
                    demanding.add(message(catalog, locale,
                            "workflow." + workflow.id() + ".transition." + id, humanize(id)));
                }
            }
        }
        if (!demanding.isEmpty()) {
            Map<String, Object> comment = new LinkedHashMap<>();
            comment.put("label", message(catalog, locale, "tql.workflow.comment", "Comment"));
            comment.put("hint", message(catalog, locale, "tql.workflow.commentRequired",
                    "Required for: {transitions}")
                    .replace("{transitions}", String.join(", ", demanding)));
            model.put("comment", comment);
        }
        if (blocked) {
            model.put("blockedReason", message(catalog, locale, "tql.workflow.assigned",
                    "Assigned to someone else — only the task holder may act."));
        }
        model.put("toolbarLabel", message(catalog, locale, "tql.workflow.actions", "Actions"));
        model.put("returnTo", pagePath);
        return model;
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
        List<String> snapshotTokens = null;
        List<Boolean> tombstones = null;
        Map<String, Object> page;
        boolean snapshot = pagination != null && io.tesseraql.yaml.model.PageSpec.SNAPSHOT
                .equals(pagination.effectiveStrategy()) && !spec.key().isEmpty();
        if (snapshot) {
            // The work queue's snapshot (docs/list-surface.md decision 10): membership frozen
            // at search time, state fetched live per page, tombstones for vanished rows.
            int size = pagination.effectiveSize();
            Object rawSnapshot = context.get("snapshot");
            if (rawSnapshot instanceof Map<?, ?> postedRaw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> posted = (Map<String, Object>) postedRaw;
                @SuppressWarnings("unchecked")
                List<String> all = (List<String>) posted.get("keys");
                long number = posted.get("number") instanceof Number n ? n.longValue() : 1;
                int from = (int) Math.min((number - 1) * size, all.size());
                int to = (int) Math.min(number * size, all.size());
                List<String> slice = all.subList(from, to);
                Map<String, Map<String, Object>> byToken = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) {
                    byToken.put(io.tesseraql.core.rows.RowTokens.encode(row, spec.key()), row);
                }
                List<Map<String, Object>> display = new ArrayList<>(slice.size());
                tombstones = new ArrayList<>(slice.size());
                for (String token : slice) {
                    Map<String, Object> row = byToken.get(token);
                    display.add(row == null ? Map.of() : row);
                    tombstones.add(row == null);
                }
                rows = display;
                snapshotTokens = slice;
                v.put("snapshotKeys", all);
                page = snapshotPage(number, size, all.size());
            } else {
                // The producer fetched cap+1 and trimmed; its hasNext IS the over-cap signal.
                // Over-cap is a user state, not an error (docs/hc-recipe-alignment.md,
                // result-cap mode B): the page renders the reject block where the table would
                // be — no rows, count withheld — and keeps the search chrome for narrowing.
                Object pageInfo = context.get("page");
                if (pageInfo instanceof Map<?, ?> info
                        && Boolean.TRUE.equals(info.get("hasNext"))) {
                    String cap = String.valueOf(pagination.effectiveCap());
                    Map<String, Object> overCap = new LinkedHashMap<>();
                    overCap.put("title", message(catalog, locale, "tql.view.overCap",
                            "More than {cap} rows match.").replace("{cap}", cap));
                    overCap.put("body", message(catalog, locale, "tql.view.overCapBody",
                            "Narrow the search to at most {cap} rows, then work the list.")
                            .replace("{cap}", cap));
                    v.put("overCap", overCap);
                    v.put("snapshotKeys", List.of());
                    rows = List.of();
                    snapshotTokens = List.of();
                    page = null;
                } else {
                    List<String> all = rowTokens(rows);
                    v.put("snapshotKeys", all);
                    int to = Math.min(size, rows.size());
                    rows = rows.subList(0, to);
                    snapshotTokens = all.subList(0, to);
                    page = snapshotPage(1, size, all.size());
                }
            }
            v.put("snapshot", true);
        } else {
            page = pager(context, params, pagePath);
            if (Boolean.TRUE.equals(data.get("truncated"))) {
                // A warn-mode maxRows truncation (docs/hc-recipe-alignment.md, result-cap
                // mode A): the shown rows ARE the cap, so the banner names their count and
                // the declared sort, and the status line hedges the total as "cap+".
                v.put("truncated",
                        truncatedBanner(catalog, locale, params, columns, rows.size()));
            }
        }
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
            String range = message(catalog, locale, "tql.view.range",
                    "{from}–{to} of {total}")
                    .replace("{from}", String.valueOf(from))
                    .replace("{to}", String.valueOf(to))
                    .replace("{total}", String.valueOf(page.get("totalRows")));
            if (snapshot) {
                // The count is the snapshot's, deliberately — only a new search changes it.
                range = range + " " + message(catalog, locale, "tql.view.asOfSearch",
                        "(as of search)");
            }
            page.put("range", range);
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
        presetModel(v, catalog, locale, params, pagePath);
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
        // The applied multi-sort as a read-out (docs/list-surface.md decision 7): the grid
        // page's toolbar says what the sort set is; a single sort stays the header's aria-sort.
        if (sort.contains(",")) {
            StringBuilder set = new StringBuilder();
            int count = 0;
            for (String token : sort.split(",")) {
                String columnName = token.startsWith("-") ? token.substring(1) : token;
                if (columnName.isBlank()) {
                    continue;
                }
                String label = rendered.stream()
                        .filter(c -> columnName.equals(c.get("name")))
                        .map(c -> String.valueOf(c.get("label")))
                        .findFirst().orElse(ViewFields.humanize(columnName));
                if (!set.isEmpty()) {
                    set.append(", ");
                }
                set.append(label).append(token.startsWith("-") ? " ↓" : " ↑");
                count++;
            }
            v.put("sortSet", message(catalog, locale, "tql.view.sortSet",
                    "Sort ({count}): {list}")
                    .replace("{count}", String.valueOf(count))
                    .replace("{list}", set));
        }
        v.put("columns", rendered);
        List<String> tokens = snapshotTokens != null ? snapshotTokens : rowTokens(rows);
        if (tombstones != null) {
            v.put("tombstones", tombstones);
        }
        if (tokens != null) {
            // The row's machine identity (docs/list-surface.md decision 2): the anchor the
            // table pattern renders, the fragment a `location: back` redirect refocuses.
            v.put("anchors", tokens.stream().map(token -> "row-" + token).toList());
        }
        String returnBase = returnBase(page, params, pagePath);
        if (!spec.actions().isEmpty() && tokens != null) {
            // Bulk actions (docs/list-surface.md decision 9): the selection column's
            // checkboxes carry the row tokens, the bar's buttons post them.
            v.put("selectable", true);
            v.put("tokens", tokens);
            List<Map<String, Object>> renderedActions = new ArrayList<>();
            for (ViewSpec.Action action : spec.actions()) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("label", message(catalog, locale, action.label(), action.label()));
                a.put("action", action.action());
                a.put("confirm", action.confirm() == null
                        ? null
                        : message(catalog, locale, action.confirm(), action.confirm()));
                renderedActions.add(a);
            }
            v.put("actions", renderedActions);
            v.put("returnTo", returnBase);
            // The row-number column (docs/bulk-report.md decision 4): a list that declares
            // actions: speaks in numbers, so the grid shows them. Snapshot and offset pages
            // know their absolute window; a keyset page is deliberately page-relative —
            // keyset's whole point is not counting.
            boolean keyset = pagination != null && io.tesseraql.yaml.model.PageSpec.KEYSET
                    .equals(pagination.effectiveStrategy());
            long numberBase = 1;
            if (!keyset && page != null) {
                if (page.get("from") instanceof Number f && !rows.isEmpty()) {
                    numberBase = f.longValue();
                } else if (page.get("number") instanceof Number n
                        && page.get("size") instanceof Number s) {
                    numberBase = (n.longValue() - 1) * s.longValue() + 1;
                }
            }
            List<Long> numbers = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                numbers.add(numberBase + i);
            }
            v.put("numbers", numbers);
        }
        bulkReportModel(v, catalog, locale, context, tokens);
        v.put("rows", cellMatrix(context, columns, rows, tokens, returnBase));
    }

    /** Entries per reason group on a list's report: the grid's row marks carry the rest. */
    private static final int BULK_ENTRY_CAP = 5;

    /**
     * Reason groups on a list's report. A bulk route's guards are a small declared vocabulary,
     * so this is generous rather than tight — but it is a bound, because a route whose refusals
     * carry per-row text has as many reasons as it has failures.
     */
    private static final int BULK_GROUP_CAP = 10;

    /**
     * The bulk feeder of the shared report (docs/bulk-report.md decisions 1-5,
     * docs/csv-import.md decision 4): the stored per-key outcomes become report entries
     * labelled by number and identity and linked by row token, plus the per-row mark and
     * re-check state the table pattern consumes. Reads the payload the round trip stored;
     * absent, expired or foreign reports simply never reach this context.
     */
    private void bulkReportModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> context, List<String> tokens) {
        if (!(context.get("bulkReport") instanceof Map<?, ?> report) || tokens == null) {
            return;
        }
        long requested = longOf(report.get("requested"));
        long succeeded = longOf(report.get("succeeded"));
        long failed = longOf(report.get("failed"));
        String summary = ViewMessages.text(catalog, locale,
                failed == 0 ? "tql.bulk.allSucceeded" : "tql.bulk.summary",
                failed == 0
                        ? "All {requested} succeeded."
                        : "{succeeded} of {requested} succeeded; {failed} failed.",
                Map.of("requested", requested, "succeeded", succeeded, "failed", failed));
        List<ReportModel.Entry> entries = new ArrayList<>();
        List<String> entryTokens = new ArrayList<>();
        if (report.get("entries") instanceof List<?> stored) {
            for (Object candidate : stored) {
                if (!(candidate instanceof Map<?, ?> entry)) {
                    continue;
                }
                String token = String.valueOf(entry.get("token"));
                String label = entry.get("number") != null
                        ? ViewMessages.text(catalog, locale, "tql.bulk.row",
                                "Row {number} — {key}",
                                Map.of("number", longOf(entry.get("number")),
                                        "key", String.valueOf(entry.get("key"))))
                        : String.valueOf(entry.get("key"));
                // A bulk entry names a row, never a column: position is not identity in a
                // grid, so the anchor is the token the row already carries.
                entries.add(new ReportModel.Entry(String.valueOf(entry.get("reason")),
                        entry.get("message") == null
                                ? null
                                : String.valueOf(entry.get("message")),
                        label, "#row-" + token, null, null));
                entryTokens.add(token);
            }
        }
        ReportModel.Rendered rendered = new ReportModel(spec.id() + "-bulk",
                failed == 0 ? "success" : "warning", summary, List.of(), entries,
                BULK_GROUP_CAP, BULK_ENTRY_CAP).render(catalog, locale);
        v.put("report", rendered.model());
        // The per-row consequences: the mark's describedby names the row's reason group,
        // and the retry set re-renders checked (docs/bulk-report.md decisions 5 and 7).
        Map<String, String> groupByToken = new LinkedHashMap<>();
        for (int i = 0; i < entryTokens.size(); i++) {
            groupByToken.putIfAbsent(entryTokens.get(i), rendered.groupIds().get(i));
        }
        List<String> attention = new ArrayList<>(tokens.size());
        List<Boolean> checked = new ArrayList<>(tokens.size());
        for (String token : tokens) {
            attention.add(groupByToken.get(token));
            checked.add(groupByToken.containsKey(token));
        }
        v.put("attention", attention);
        v.put("checked", checked);
    }

    /** A stored report number, whatever width JSON round-tripping gave it. */
    private static long longOf(Object value) {
        return value instanceof Number n ? n.longValue() : 0;
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
        if (pagePath.isEmpty()) {
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

    /**
     * The mode A truncation banner (docs/hc-recipe-alignment.md, result-cap): a persistent
     * warning naming how many rows are shown and, when the current sort names a declared
     * column, first by what — "the first N" is meaningless until the user knows the order.
     * The count line hedges the total as "N+"; the exact total is the query the cap avoided.
     */
    private static Map<String, Object> truncatedBanner(MessageCatalog catalog, Locale locale,
            Map<String, Object> params, List<ViewSpec.Column> columns, int shown) {
        String max = String.valueOf(shown);
        String sortLabel = null;
        String sort = str(params.get("sort"));
        for (ViewSpec.Column column : columns) {
            if (column.name().equals(sort)) {
                sortLabel = column.label() == null ? column.name() : column.label();
            }
        }
        Map<String, Object> banner = new LinkedHashMap<>();
        banner.put("title", message(catalog, locale, "tql.view.truncated",
                "Showing the first {max} rows.").replace("{max}", max));
        String body = sortLabel == null
                ? message(catalog, locale, "tql.view.truncatedBody",
                        "More than {max} rows match. Narrow the search or filters to see"
                                + " the rest.")
                : message(catalog, locale, "tql.view.truncatedSorted",
                        "More than {max} rows match, sorted by {sort}. Narrow the search or"
                                + " filters to see the rest.")
                        .replace("{sort}", sortLabel);
        banner.put("body", body.replace("{max}", max));
        banner.put("count", message(catalog, locale, "tql.view.truncatedCount",
                "{max}+ results").replace("{max}", max));
        return banner;
    }

    /** The synthetic page window of a snapshot slice — the membership is the total. */
    private static Map<String, Object> snapshotPage(long number, int size, int total) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("number", number);
        page.put("size", size);
        page.put("totalRows", total);
        page.put("totalPages", Math.max(1, (total + size - 1L) / size));
        page.put("hasNext", number * (long) size < total);
        page.put("hasPrev", number > 1);
        return page;
    }

    /**
     * The named view presets (docs/list-surface.md decision 8): real links, no storage. The
     * active preset is the one whose every param the current URL carries; "Modified" marks an
     * applied filter or search the active preset does not pin — a tweaked view, still
     * recognizably that view. Re-clicking the active link is the reset.
     */
    private void presetModel(Map<String, Object> v, MessageCatalog catalog, Locale locale,
            Map<String, Object> params, String pagePath) {
        if (spec.presets().isEmpty()) {
            return;
        }
        List<Map<String, Object>> rendered = new ArrayList<>();
        ViewSpec.Preset firstActive = null;
        for (ViewSpec.Preset preset : spec.presets()) {
            StringBuilder query = new StringBuilder();
            boolean active = true;
            for (Map.Entry<String, String> param : preset.params().entrySet()) {
                query.append('&').append(param.getKey()).append('=')
                        .append(encode(param.getValue()));
                if (!param.getValue().equals(str(params.get(param.getKey())))) {
                    active = false;
                }
            }
            if (active && firstActive == null) {
                firstActive = preset;
            }
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("label", message(catalog, locale, preset.name(), preset.name()));
            p.put("href", href(pagePath, query.toString()));
            p.put("active", active);
            // Model-computed attribute values: the kit-markup guard verifies every literal
            // data-variant in a template against the kit's selectors.
            p.put("variant", active ? "primary" : "ghost");
            p.put("current", active ? "page" : null);
            rendered.add(p);
        }
        v.put("presets", rendered);
        v.put("presetModified", firstActive != null
                && hasStateBeyond(firstActive, params));
    }

    /** Whether an applied filter or search term goes beyond what the active preset pins. */
    private boolean hasStateBeyond(ViewSpec.Preset preset, Map<String, Object> params) {
        List<String> conditions = new ArrayList<>();
        spec.filters().forEach(filter -> conditions.add(filter.name()));
        if (spec.search() != null) {
            conditions.add(spec.search());
        }
        for (String name : conditions) {
            String value = str(params.get(name));
            if (!value.isEmpty() && !value.equals(preset.params().get(name))) {
                return true;
            }
        }
        return false;
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

    /**
     * The lock value in the rendered row: an exact key, then a case-insensitive scan for the
     * dialects that fold result-set labels, and nothing further — the resolution
     * {@link LookupReferences#column} performs for a lookup's declared columns.
     *
     * <p>Absent and present-but-null are both refusals, with different messages because they have
     * opposite fixes: project the column, or fix the data. A null lock compares against nothing —
     * an equality predicate on null matches no row — so the form would be unsaveable rather than
     * unlocked, which is worse than either.
     *
     * <p>A value with no textual form refuses too. SQL Server's {@code rowversion} arrives as a
     * {@code byte[]}, whose string form is an identity hash — a different one on every render —
     * so the lock could never match and the record would be permanently unsaveable. Better to
     * say so at the render than to ship a hidden field that is guaranteed to conflict.
     *
     * <p>The check is at render, not at build, on the {@code TQL-VIEW-3329} precedent:
     * {@code select *} makes a static column check a liar. A masked lock column cannot reach
     * here: a view declaring a read policy for it is refused at build, because a masked value
     * survives as a present, non-null key and would sail past every check below into a form that
     * can never be saved.
     */
    private Object lockValue(Map<String, Object> row) {
        String key = row.containsKey(lockColumn) ? lockColumn : null;
        if (key == null) {
            for (String candidate : row.keySet()) {
                if (candidate.equalsIgnoreCase(lockColumn)) {
                    key = candidate;
                    break;
                }
            }
        }
        if (key == null) {
            throw new TqlException(MISSING_LOCK, "View " + spec.id() + ": the action route's lock"
                    + " column '" + lockColumn + "' is not in the rendered row — the view's own"
                    + " source: must select it");
        }
        Object value = row.get(key);
        if (value == null) {
            throw new TqlException(MISSING_LOCK, "View " + spec.id() + ": the action route's lock"
                    + " column '" + lockColumn + "' is null in the rendered row — a null lock"
                    + " compares against nothing, so the form would be unsaveable");
        }
        if (value.getClass().isArray() || value instanceof Map || value instanceof List) {
            throw new TqlException(MISSING_LOCK, "View " + spec.id() + ": the action route's lock"
                    + " column '" + lockColumn + "' holds a " + value.getClass().getSimpleName()
                    + ", which has no textual form a form field can send back unchanged");
        }
        return value;
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
        return ViewMessages.text(catalog, locale, key, fallback);
    }

    private static String humanize(String name) {
        return ViewFields.humanize(name);
    }
}
