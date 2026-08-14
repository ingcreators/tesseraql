package io.tesseraql.studio;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.ResponseHeaderDefaults;
import io.tesseraql.yaml.config.SecurityDefaults;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.MigrationFile;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.menu.MenuSpec.MenuItem;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.ResponseSpec;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldChecksum;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The backend for TesseraQL Studio (design ch. 16): an explorer over the app's routes and jobs,
 * read-only access to their source files, and draft editing.
 *
 * <p>All file access is confined to the app home (no {@code ../} traversal, design ch. 20.2). In
 * read-only mode — the default for production — draft writes are rejected so Studio can be safely
 * exposed against a running app (ch. 16.9).
 */
public final class StudioService {

    static final TqlErrorCode READ_ONLY = new TqlErrorCode(TqlDomain.STUDIO, 4030);
    static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.STUDIO, 4040);
    static final TqlErrorCode CONNECTORS = new TqlErrorCode(TqlDomain.STUDIO, 4231);
    private static final TqlErrorCode RECORDER = new TqlErrorCode(TqlDomain.STUDIO, 4233);
    /** Capturing a baseline without a schema sidecar to copy (409). */
    private static final TqlErrorCode NO_SCHEMA = new TqlErrorCode(TqlDomain.STUDIO, 4236);
    private static final java.util.regex.Pattern SECRET_REF = java.util.regex.Pattern
            .compile("\\$\\{secret\\.[A-Za-z0-9_.-]+\\}");
    private static final TqlErrorCode NEW_ROUTE = new TqlErrorCode(TqlDomain.STUDIO, 4224);
    static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.STUDIO, 4090);
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\d+");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern MIGRATION_PATH = Pattern
            .compile("db/(?:[^/]+/)?migration(?:-[^/]+)?/[^/]+\\.sql");

    /** The HTTP-method stems that name a route document under {@code web/} (and its fixtures). */
    private static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete",
            "head", "options");

    /** Where a source-writing operation records who did what ({@link #recordAudit}). */
    @FunctionalInterface
    interface AuditRecorder {
        void record(String actor, String action, String target);
    }

    private final SimpleYamlParser parser = new SimpleYamlParser();
    private final ObjectMapper jsonMapper = new ObjectMapper();
    private final boolean readOnly;
    private final DraftStore draftStore;
    private final OverlayEditor overlayEditor;
    private final AuditTrail auditTrail;
    private final PreviewRenderer renderer;
    private final DecisionForms decisionForms;
    private final CalendarForms calendarForms;
    private final JobPolicyForms jobPolicyForms;
    private final RouteForms routeForms;
    private AppManifest manifest;
    private Path appHome;

    public StudioService(AppManifest manifest, boolean readOnly) {
        this.manifest = manifest;
        this.appHome = manifest.appHome();
        this.readOnly = readOnly;
        // The collaborators read the app home through a supplier — reload() reassigns the field,
        // and a captured value would pin them to a stale manifest.
        this.auditTrail = new AuditTrail(() -> appHome);
        this.renderer = new PreviewRenderer(() -> appHome, () -> manifest, this::source,
                this::sourceIfExists, this::resolve);

        this.draftStore = new DraftStore(() -> appHome, readOnly, this::preview,
                this::recordAudit);
        this.overlayEditor = new OverlayEditor(() -> appHome, readOnly, draftStore::resolve,
                this::recordAudit);
        Declarations declarations = new Declarations(() -> appHome, draftStore, readOnly,
                this::recordAudit);
        this.decisionForms = new DecisionForms(declarations);
        this.jobPolicyForms = new JobPolicyForms(declarations, () -> manifest);
        this.routeForms = new RouteForms(declarations);
        this.calendarForms = new CalendarForms(declarations);
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** A summary of every route and job in the app, for the explorer view. */
    public Explorer explorer() {
        return explorer(null);
    }

    /**
     * The explorer view narrowed to routes and jobs matching {@code query} (Studio backlog C4): a
     * case-insensitive substring over each entry's id, source path, recipe, and (for a route) its
     * HTTP method and URL path. A blank or null query matches everything. The directory tree the view
     * renders is built from the matching entries' source paths, so filtering prunes the tree.
     */
    public Explorer explorer(String query) {
        String appName = manifest.config().getString("tesseraql.app.name").orElse("app");
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<RouteSummary> routes = manifest.routes().stream()
                .map(this::routeSummary)
                .filter(route -> q.isEmpty() || routeMatches(route, q))
                .sorted(java.util.Comparator.comparing(RouteSummary::id))
                .toList();
        List<JobSummary> jobs = manifest.jobs().stream()
                .map(this::jobSummary)
                .filter(job -> q.isEmpty() || jobMatches(job, q))
                .sorted(java.util.Comparator.comparing(JobSummary::id))
                .toList();
        // Pending drafts, filtered by the same query over their path so filtering prunes the draft
        // markers with the tree. A draft whose path is a served route/job source marks that leaf as
        // edited; a new (not-yet-served) draft becomes its own pending node (StudioViews.tree).
        List<DraftSummary> drafts = drafts().stream()
                .filter(draft -> q.isEmpty() || contains(draft.path(), q))
                .toList();
        return new Explorer(appName, readOnly, routes, jobs, drafts);
    }

    private static boolean routeMatches(RouteSummary route, String q) {
        return contains(route.id(), q) || contains(route.method(), q) || contains(route.path(), q)
                || contains(route.recipe(), q) || contains(route.source(), q);
    }

    private static boolean jobMatches(JobSummary job, String q) {
        return contains(job.id(), q) || contains(job.recipe(), q) || contains(job.source(), q);
    }

    private static boolean contains(String value, String lowerQuery) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lowerQuery);
    }

    /** Reads a source file (YAML/SQL/template) by its app-relative path. */
    public String source(String relativePath) {
        String content = sourceIfExists(relativePath);
        if (content == null) {
            throw new TqlException(NOT_FOUND, "No such file: " + relativePath);
        }
        return content;
    }

    /**
     * Reads a source file by its app-relative path, or {@code null} when no such file exists — the
     * case of a draft for a not-yet-applied new file, where there is no source to compare against.
     */
    public String sourceIfExists(String relativePath) {
        return draftStore.sourceIfExists(relativePath);
    }

    /**
     * Saves a draft edit of {@code relativePath} under {@code work/studio/drafts} without touching
     * the source of truth (design ch. 16.7). Rejected in read-only mode.
     */
    public Path saveDraft(String relativePath, String content) {
        return draftStore.saveDraft(relativePath, content);
    }

    /**
     * Discards a saved draft of {@code relativePath} without touching the source of truth, so an
     * edit can be abandoned and the editor falls back to the source. Rejected in read-only mode;
     * idempotent (a no-op when no draft exists).
     *
     * @return whether a draft was actually removed
     */
    public boolean deleteDraft(String relativePath) {
        return draftStore.deleteDraft(relativePath);
    }

    /**
     * Whether applying {@code relativePath}'s draft would overwrite a source that changed since the
     * draft was started (Studio backlog D5): the recorded base differs from the current source. False
     * when there is no draft or no recorded base (e.g. a draft from before base tracking).
     */
    public boolean draftConflicts(String relativePath) {
        return draftStore.draftConflicts(relativePath);
    }

    /**
     * Renders a draft (or current source) against a sample model and returns the actual output
     * (design ch. 16.6, Studio backlog A1) — the step past {@link #preview}, which only proves a
     * data-dependent template parses. Two shapes render:
     *
     * <ul>
     *   <li>a <b>template file</b> ({@code .html}/{@code .tpl}) renders against {@code sampleModel}
     *       read as the template's top-level variables;</li>
     *   <li>a <b>web route</b> ({@code web/**}/{@code <method>.yml}) renders its {@code response}
     *       against {@code sampleModel} read as the execution context (e.g. {@code params},
     *       {@code main.rows}): a {@code query-html}/{@code page} route resolves
     *       {@code response.html.model} and renders the route's template, a {@code query-json} route
     *       resolves {@code response.json.body} and pretty-prints it.</li>
     * </ul>
     *
     * <p>{@code sampleModel} is YAML/JSON; when blank it falls back to the colocated
     * {@code *.sample.yml} fixture, then an empty model. The same three-resolver engine as
     * {@link #preview} resolves {@code tql/*} fragments and sibling app templates, so HTML output
     * matches a real response. A {@code query-json} route's output-field masking
     * ({@code response.json.fields}) is applied when a {@link FieldMask} is supplied.
     */
    public RenderResult render(String relativePath, String content, String sampleModel) {
        return render(relativePath, content, sampleModel, null, null);
    }

    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows) {
        return render(relativePath, content, sampleModel, liveRows, null);
    }

    /**
     * As {@link #render(String, String, String)}, but for a web route a non-null {@code liveRows}
     * supplies the {@code sql} rows by executing the route's query against the dev datasource (the
     * Studio backlog A1 "real bound params" end, powered by the A2 sandbox), and a non-null
     * {@code fieldMask} applies a {@code query-json} route's {@code response.json.fields} output
     * masking to the resolved body (Studio backlog A1 follow-up). Studio itself stays free of the
     * security/compiler stack: the caller (the runtime) provides the sandboxed row source and the
     * mask over the canonical field-policy applier.
     */
    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows, FieldMask fieldMask) {
        return render(relativePath, content, sampleModel, liveRows, fieldMask, null);
    }

    /**
     * As {@link #render(String, String, String, RowSource, FieldMask)}, but a non-null
     * {@code pdfRender} renders a {@code query-export} {@code format: pdf} route's PDF from its query
     * rows (Studio backlog A1 follow-up — PDF preview), returned as a {@code data:} URL. Studio stays
     * free of the heavy (optional) PDF stack: the runtime provides the renderer over the canonical PDF
     * codec when the {@code tesseraql-pdf} module is on the classpath.
     */
    public RenderResult render(String relativePath, String content, String sampleModel,
            RowSource liveRows, FieldMask fieldMask, PdfRender pdfRender) {
        return renderer.render(relativePath, content, sampleModel, liveRows, fieldMask, pdfRender);
    }

    /**
     * Whether a draft parses as what its path says it is, and what it says it declares — the
     * gate {@link #applyDraft} runs before a draft becomes the source of truth.
     */
    public PreviewResult preview(String relativePath, String content) {
        return renderer.preview(relativePath, content);
    }

    /**
     * The parsed sample model — the Studio mail test-send renders the body through
     * {@link #render} and needs the same {@code payload}/{@code event} maps for the
     * subject line's inline template.
     */
    public Map<String, Object> sampleModelMap(String relativePath, String sampleModel) {
        return renderer.sampleModel(relativePath, sampleModel);
    }

    /**
     * The colocated sample-data fixture for a renderable file — {@code <base>.sample.yml} next to
     * it — or null when the file is not renderable or no fixture exists.
     */
    public String sampleModel(String relativePath) {
        return renderer.sampleFixture(relativePath);
    }

    /**
     * Applies a {@code query-json} route's {@code response.json.fields} output masking to the
     * resolved body (Studio backlog A1 follow-up): hide/redact fields per their policy, evaluated for
     * the sample principal in {@code context.get("principal")}. Implemented by the runtime over the
     * canonical {@code FieldPolicyApplier} so Studio stays free of the security/compiler stack.
     */
    @FunctionalInterface
    public interface FieldMask {
        /** The masked body for {@code fields}, evaluated against the sample principal in context. */
        Object mask(Map<String, ResponseSpec.FieldPolicy> fields, Object body,
                Map<String, Object> context);
    }

    /**
     * Renders a {@code query-export} {@code format: pdf} route to PDF bytes from its query
     * {@code rows} (Studio backlog A1 follow-up — PDF preview). Implemented by the runtime over the
     * canonical PDF codec so Studio stays free of the optional {@code tesseraql-pdf} stack; returns
     * {@code null} when no PDF codec is on the classpath.
     */
    @FunctionalInterface
    public interface PdfRender {
        /** PDF bytes for the export route, or null when the {@code tesseraql-pdf} module is absent. */
        byte[] render(io.tesseraql.yaml.model.ExportSpec export, Path routeDir,
                List<Map<String, Object>> rows);
    }

    /**
     * Supplies live read results for a route render by executing its queries against the dev
     * datasource (Studio backlog A1/A2; multi-binding): the main {@code sql} <em>and</em> every
     * named {@code query}, each keyed by its model name. Implemented by the runtime over the
     * sandboxed datasource so Studio stays database-free.
     */
    @FunctionalInterface
    public interface RowSource {
        /**
         * The live results keyed by model name — the main query under {@code sql} and each named
         * {@code query} under its own name, each a {@code {rows, rowCount}} map — with bind params
         * resolved from {@code context} in authored order (so a later query may read an earlier
         * one's result). Null or empty keeps the hand-authored sample. May throw to report a failure.
         */
        Map<String, Object> rowsFor(RouteDefinition route, Path routeDir,
                Map<String, Object> context);
    }

    /**
     * The scaffolder preview and apply share — built over the app's declared security defaults so
     * both emit byte-identical files (and match the CLI, which resolves the same config).
     */
    private CrudScaffolder crudScaffolder() {
        return new CrudScaffolder(SecurityDefaults.from(manifest.config()),
                ResponseHeaderDefaults.from(manifest.config()),
                io.tesseraql.yaml.catalog.Catalogs.load(manifest.appHome()).all().values()
                        .stream().map(io.tesseraql.yaml.model.CatalogSpec::table)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }

    /**
     * Previews the CRUD slice the scaffold would generate for an introspected table (Studio backlog
     * B3, roadmap Phase 23) without writing anything: every generated file with its content and the
     * disposition an {@link io.tesseraql.yaml.scaffold.ScaffoldWriter} apply would give it. Studio
     * itself stays database-free — the caller (the runtime) introspects the dev datasource and hands
     * over the {@link TableSchema}, exactly the shape the CLI {@code scaffold crud} works from, so the
     * preview and a later apply are byte-identical to the command-line generator.
     *
     * <p>Each file's {@code status} mirrors {@code ScaffoldWriter.decide}: {@code new} (no such file
     * yet), {@code unchanged} (already byte-identical to the generation), {@code regenerate} (a
     * pristine generated file an apply would overwrite), or {@code conflict} (a file the user edited
     * or owns, which an apply leaves alone unless forced). Generation throws when the table cannot be
     * scaffolded (e.g. a composite or missing primary key).
     */
    public ScaffoldPreview scaffoldPreview(TableSchema table) {
        List<ScaffoldFile> files = new ArrayList<>();
        int writes = 0;
        int conflicts = 0;
        for (ScaffoldedFile file : crudScaffolder().scaffold(table)) {
            String status = scaffoldStatus(file);
            files.add(new ScaffoldFile(file.path(), file.content(), status));
            if ("new".equals(status) || "regenerate".equals(status)) {
                writes++;
            } else if ("conflict".equals(status)) {
                conflicts++;
            }
        }
        return new ScaffoldPreview(table.name(), files, files.size(), writes, conflicts);
    }

    /**
     * Writes a table's scaffolded CRUD slice into the app home (Studio backlog B3, roadmap Phase 23),
     * honoring the scaffold's edit-detection contract: a pristine generated file is regenerated in
     * place, but a file the user edited or owns is left alone and reported as skipped unless
     * {@code force} overrides it. Rejected in read-only mode.
     *
     * <p>Generation is the same pure {@link CrudScaffolder} the preview and the CLI use, so the
     * written files are byte-identical across all three. Newly written route documents
     * ({@code web/**}/{@code <method>.yml} the manifest did not already declare) are reported
     * separately: since Phase 42 the hot reloader also mounts new routes, so applying serves immediately
     * (design ch. 16.8).
     */
    public ScaffoldResult scaffoldApply(TableSchema table, boolean force) {
        return scaffoldApply(table, force, null);
    }

    /**
     * As {@link #scaffoldApply(TableSchema, boolean)}, but {@code actor} (the caller) is recorded to
     * the audit trail once files are written (Studio backlog D6).
     */
    public ScaffoldResult scaffoldApply(TableSchema table, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; scaffolding is disabled");
        }
        List<ScaffoldedFile> files = crudScaffolder().scaffold(table);
        Set<String> existingRoutes = manifest.routes().stream()
                .map(route -> relative(route.source()))
                .collect(java.util.stream.Collectors.toSet());
        ScaffoldWriter.Report report = new ScaffoldWriter().apply(appHome, files, force);
        List<String> newRoutes = report.written().stream()
                .filter(StudioService::isRouteYaml)
                .filter(path -> !existingRoutes.contains(path))
                .toList();
        if (!report.written().isEmpty()) {
            recordAudit(actor, "scaffold", table.name());
        }
        return new ScaffoldResult(table.name(), report.written(), report.unchanged(),
                report.skipped(), newRoutes, report.blocked());
    }

    /**
     * Creates a new route from a starter skeleton for the given {@code recipe} (Studio backlog B3):
     * it saves the skeleton as a draft at {@code path} — a {@code web/**}/{@code <method>.yml} file
     * that must not already exist — so the source editor's validate → apply flow then finishes
     * creating it (applying serves it immediately — routes hot-reload since Phase 42). Rejected
     * in read-only mode. Returns the draft path.
     */
    public Path newRouteDraft(String path, String recipe) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; creating routes is disabled");
        }
        if (!isRouteYaml(path)) {
            throw new TqlException(NEW_ROUTE,
                    "A new route path must be a web/**/<method>.yml file: " + path);
        }
        if (sourceIfExists(path) != null) {
            throw new TqlException(NEW_ROUTE, "A file already exists at " + path
                    + "; open it in the editor instead");
        }
        return saveDraft(path, starterRoute(path, recipe));
    }

    /** One structured input row of the form-driven route editor (Track J1). */
    public record FormInput(String name, String type, boolean required, String min, String max,
            String maxLength, String minLength, String pattern, String enumCsv, String domain) {
    }

    /**
     * The form-driven route editor's read model (Track J1): the governed fields — recipe, auth,
     * policy, CSRF, inputs — parsed from the pending draft when one exists, else the served
     * source. {@code error} carries the parse failure when the document cannot be read as YAML
     * (the form then points at the text editor instead of rendering fields).
     */
    public record RouteForm(String path, String id, String recipe, String auth, String policy,
            String csrf, List<FormInput> inputs, boolean fromDraft, String error) {
    }

    /**
     * The app's declared field-domain names (docs/field-domains.md), sorted — the route form's
     * domain select and the docs portal draw from the same loader the manifest resolves with.
     */
    public List<String> domainNames() {
        return io.tesseraql.yaml.domain.FieldDomains.load(appHome).domains().keySet().stream()
                .sorted().toList();
    }

    /**
     * Shared rule names with their bind contracts, for the validation builder's {@code use:}
     * option — the contract has to travel with the name, because a reference must wire it
     * exactly and the author cannot be expected to remember it.
     */
    public List<SharedRule> sharedRules() {
        var declared = io.tesseraql.yaml.rules.ValidationRuleSets.load(appHome,
                new io.tesseraql.yaml.SimpleYamlParser());
        List<SharedRule> rules = new ArrayList<>();
        declared.rules().forEach((name, rule) -> rules.add(new SharedRule(name, rule.binds())));
        rules.sort(java.util.Comparator.comparing(SharedRule::name));
        return rules;
    }

    /** One shared rule offered by the validation builder: its name and its bind contract. */
    public record SharedRule(String name, java.util.Map<String, String> binds) {
    }

    /** The decision-rows grid's fixed slot count: rows {@code r0..r19}. */
    public static final int DECISION_GRID_ROWS = 20;
    /** The decision-rows grid's fixed slot count: columns {@code c0..c11}, inputs then outputs. */
    public static final int DECISION_GRID_COLUMNS = 12;

    /**
     * One declared decision offered by the decide builder: its name, its input contract,
     * whether its table source is dated ({@code effective:} columns, so {@code effectiveAt:}
     * applies), and whether its rows live in YAML (the rows-grid editor's precondition).
     */
    public record SharedDecision(String name, List<String> inputs, boolean dated,
            boolean yamlBacked) {
    }

    /** One posted grid column: the declaration key, {@code in}/{@code out}, its cells by row. */
    public record DecisionColumn(String key, String kind, List<String> cells) {
    }

    /**
     * The rows-grid model: the decisions file the decision lives in (app-relative), whether the
     * grid edits the pending draft, whether the decision fits the fixed slots, the ordered
     * columns, and each authored row's cell text aligned to the columns.
     */
    public record DecisionGrid(String name, String path, boolean yamlBacked, boolean fromDraft,
            boolean tooLarge, List<GridColumn> columns, List<List<String>> rows, String error) {
    }

    /** One grid column: the input/output name, {@code in}/{@code out}, and a header hint. */
    public record GridColumn(String key, String kind, String hint) {
    }

    /**
     * One declared calendar: its effective weekend (the Saturday/Sunday default made
     * explicit) and its holiday home — {@code dates} when fixed, {@code sourceTable} when
     * operations maintains the rows.
     */
    public record CalendarSummary(String name, String source, List<String> weekend,
            boolean tableBacked, String sourceTable, List<String> dates) {
    }

    /** One day cell of the month grid; a day outside the month renders dimmed. */
    public record CalendarDayCell(int day, boolean inMonth, boolean business, boolean weekend,
            boolean holiday, boolean nominal, boolean lands) {
    }

    /** The month-grid model, weeks Monday-first, with prev/next month cursors. */
    public record CalendarMonthGrid(String name, String month, String monthLabel,
            String prevMonth, String nextMonth, List<List<CalendarDayCell>> weeks,
            boolean tableBacked, Integer dayOfMonth, String shift, String error) {
    }

    /** The draft-aware calendar edit state: see {@link #calendarEditState(String)}. */
    public record CalendarEditState(List<String> weekend, List<String> dates,
            boolean tableBacked, boolean draftPending) {
    }

    /** The structured job-policy fields; {@code pollTriggered} sends the form away. */
    public record JobPolicyForm(String jobId, String path, boolean pollTriggered, String cron,
            String fixedDelay, String calendar, String runOn, String dayOfMonth, String shift,
            String after, String overlap, String slaCompleteBy, String slaRunningLongerThan) {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> anyMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    static String scalar(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    static String csvOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        return list.stream().map(String::valueOf).collect(java.util.stream.Collectors
                .joining(", "));
    }

    static void putOrRemove(Map<String, Object> map, String key, Object value) {
        if (value == null) {
            map.remove(key);
        } else {
            map.put(key, value);
        }
    }

    /**
     * True when {@code value} is a pure secret <i>reference</i> ({@code ${secret.<provider>.<key>}},
     * no literal default fallback). Studio's connector/SSO authoring (Track J2) only ever writes
     * references — a literal secret value is rejected before it can reach a config file.
     */
    public static boolean isSecretReference(String value) {
        return value != null && SECRET_REF.matcher(value.trim()).matches();
    }

    /**
     * Whether an API-console invocation of {@code method path} can be saved as a declarative
     * test case (Track J3). v1 records query routes whose main binding is a plain SQL file read
     * with no path parameters — the recorded case is a {@code sql:} case, so it runs in CI's
     * sandboxed runner exactly like a hand-written one.
     */
    public Map<String, Object> recordability(String method, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        RouteFile match = routeFor(method, path);
        if (match == null) {
            out.put("recordable", false);
            out.put("reason", "No served route matches " + method + " " + path);
            return out;
        }
        out.put("routeId", match.definition().id());
        Binding sql = match.definition().main();
        if (sql == null || sql.file() == null
                || (sql.mode() != null && !"query".equals(sql.mode()))) {
            out.put("recordable", false);
            out.put("reason", "Only a query route with a bound SQL file records as a test case");
            return out;
        }
        if (path.contains("{")) {
            out.put("recordable", false);
            out.put("reason", "Routes with path parameters are not recordable yet");
            return out;
        }
        out.put("recordable", true);
        return out;
    }

    private RouteFile routeFor(String method, String path) {
        if (method == null || path == null) {
            return null;
        }
        for (RouteFile route : manifest.routes()) {
            if (path.equals(route.urlPath()) && method.equalsIgnoreCase(route.httpMethod())) {
                return route;
            }
        }
        return null;
    }

    /** The matched route's main SQL file as an app-relative path (Track J3), else null. */
    public String recordedSqlFile(String method, String path) {
        RouteFile match = routeFor(method, path);
        if (match == null || match.definition().main() == null
                || match.definition().main().file() == null) {
            return null;
        }
        Path routeDir = match.source().getParent();
        return appHome.relativize(routeDir.resolve(match.definition().main().file()))
                .toString().replace('\\', '/');
    }

    /**
     * Reverse-maps a console invocation onto the route's {@code sql.params} (Track J3): each SQL
     * parameter's source expression ({@code query.x} / {@code params.x}) resolves from the sent
     * query string and JSON body; unresolved parameters are simply omitted (the 2-way SQL's
     * conditional blocks then skip them, exactly as the live request did).
     */
    public Map<String, Object> recordedCaseParams(String method, String path,
            Map<String, String> query, Map<String, Object> body) {
        RouteFile match = routeFor(method, path);
        if (match == null || match.definition().main() == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, String> mapping = match.definition().main().params();
        if (mapping == null) {
            return out;
        }
        mapping.forEach((sqlParam, expr) -> {
            Object value = null;
            if (expr != null && expr.startsWith("query.")) {
                value = query.get(expr.substring("query.".length()));
            } else if (expr != null && expr.startsWith("params.")) {
                String name = expr.substring("params.".length());
                value = body.containsKey(name) ? body.get(name) : query.get(name);
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                out.put(sqlParam, value);
            }
        });
        return out;
    }

    /**
     * Appends a recorded test case to {@code tests/studio-recorded-test.yml} (Track J3): the
     * console invocation becomes a {@code sql:} case with the reverse-mapped params and — when
     * the sandbox captured one — an {@code expect.rowCount}, so the manual check runs as a
     * regression test from then on. Duplicate names get a numeric suffix. Audited.
     */
    public String appendRecordedTest(String name, String sqlFile, Map<String, Object> params,
            Integer rowCount, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY, "Studio is read-only; recording tests is disabled");
        }
        if (sqlFile == null) {
            throw new TqlException(RECORDER, "This invocation is not recordable as a test case");
        }
        Path file = resolve("tests/studio-recorded-test.yml");
        Map<String, Object> tree;
        if (Files.isRegularFile(file)) {
            tree = mutableCopy(parser.parseTree(file));
        } else {
            tree = new LinkedHashMap<>();
        }
        // Every document family carries the version discriminator (vocabulary-cleanup
        // slice 2); the recorder writes it like any hand-authored suite.
        tree.putIfAbsent("version", "tesseraql/v1");
        Object existing = tree.get("tests");
        List<Object> tests = existing instanceof List<?> list
                ? new ArrayList<>(list)
                : new ArrayList<>();
        String base = trimToNull(name) == null ? "recorded " + sqlFile : name.trim();
        String unique = base;
        int suffix = 2;
        while (hasCaseNamed(tests, unique)) {
            unique = base + " (" + suffix++ + ")";
        }
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("name", unique);
        testCase.put("sql", Map.of("file", sqlFile));
        if (!params.isEmpty()) {
            testCase.put("params", params);
        }
        if (rowCount != null) {
            testCase.put("expect", Map.of("rowCount", rowCount));
        }
        tests.add(testCase);
        tree.put("tests", tests);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, parser.write(tree));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "test", "tests/studio-recorded-test.yml");
        return unique;
    }

    private static boolean hasCaseNamed(List<Object> tests, String name) {
        for (Object test : tests) {
            if (test instanceof Map<?, ?> map && name.equals(map.get("name"))) {
                return true;
            }
        }
        return false;
    }

    /** The setup wizards whose {@code .yml.tpl} the Review step may render (slice 5). */
    private static final Set<String> WIZARD_KINDS = Set.of("identity", "oidc", "saml", "scim");

    private static final TqlErrorCode WIZARD_UNKNOWN = new TqlErrorCode(TqlDomain.STUDIO, 4240);

    /**
     * Renders a setup wizard's {@code .yml.tpl} — the SAME template its download serves — for the
     * Review-YAML step (docs/studio-ux-refresh.md slice 5), so what is previewed is what either
     * path produces. {@code studioAppRoot} is the mounted studio app's extracted tree; every
     * template variable resolves from {@code params} with a blank default, so a half-filled form
     * previews with blanks instead of erroring. An unknown {@code kind} is refused
     * ({@code TQL-STUDIO-4240}) — the template name is built from a fixed whitelist, never from
     * caller input.
     */
    public static String renderWizardYaml(Path studioAppRoot, String kind,
            Map<String, Object> params) {
        if (kind == null || !WIZARD_KINDS.contains(kind)) {
            throw new TqlException(WIZARD_UNKNOWN,
                    "Unknown wizard '" + kind + "' — expected one of " + WIZARD_KINDS);
        }
        Map<String, Object> model = new LinkedHashMap<>();
        params.forEach((key, value) -> model.put(key,
                value == null ? "" : String.valueOf(value)));
        return io.tesseraql.yaml.template.Templates.render(studioAppRoot,
                "web/_tesseraql/studio/ui/wizard/" + kind + "/wizard-" + kind + ".yml.tpl",
                model);
    }

    /** Blank stays {@code null}; anything else must be a valid secret reference (Track J2). */
    public static String secretReferenceOrNull(String field, String value) {
        String clean = trimToNull(value);
        return clean == null ? null : requireSecretReference(field, clean);
    }

    static String requireSecretReference(String field, String value) {
        if (!isSecretReference(value)) {
            throw new TqlException(CONNECTORS, field + " must be a secret reference like "
                    + "${secret.env.NAME} — literal secret values are never written from Studio");
        }
        return value.trim();
    }

    /**
     * Sets one dotted-path key in {@code config/overlay.yml} (Track J2 write-through), other keys
     * preserved; a {@code null} value removes the leaf. Restart-bound settings stay honest at the
     * call site — this method only makes the write durable and audited.
     */
    public void setOverlayPath(String dottedKey, Object value, String action, String actor) {
        overlayEditor.setOverlayPath(dottedKey, value, action, actor);
    }

    /** Sentinel accepted by {@link #writeOverlaySection}: remove the leaf instead of setting it. */
    public static final Object REMOVE = new Object();

    /**
     * Applies several dotted-path writes to {@code config/overlay.yml} in one save (Track J2):
     * the wizard write-through and connector editors compose their sections from this. Values are
     * scalars, lists, or maps; {@link #REMOVE} deletes the leaf.
     *
     * <p><strong>The whole file is re-serialized canonically.</strong> A single-key edit round-trips
     * {@code config/overlay.yml} through the parser to a plain map and back, so every comment and
     * all hand formatting in it are lost — including in sections the caller never touched. This is
     * the same trade {@code routeFormSave} makes, and it says so in its javadoc and on its screen;
     * this one silently did not (docs/silent-tolerance.md T9). The overlay-writing screens carry
     * the note now.
     */
    public void writeOverlaySection(Map<String, Object> values, String action, String actor) {
        overlayEditor.writeOverlaySection(values, action, actor);
    }

    /** The effective (merged, overlay-included) string list at {@code dottedKey}; empty when absent. */
    public List<String> effectiveStringList(String dottedKey) {
        return overlayEditor.effectiveStringList(dottedKey);
    }

    /**
     * Adds or removes one egress allow-list host (Track J2). Deep-merge replaces lists, so the
     * overlay carries the FULL effective list after the change — base-config hosts included.
     * The caller gates this behind the confirm dialog (egress changes widen the app's reach).
     */
    public void updateEgressHosts(String scope, String host, boolean remove, String actor) {
        overlayEditor.updateEgressHosts(scope, host, remove, actor);
    }

    /**
     * Adds or replaces an inbound-webhook verifier (Track J2): the HMAC secret is a validated
     * secret <i>reference</i>, never a value. Applies on the next start (verifiers load at boot).
     */
    public void writeWebhookVerifier(String name, String secretRef, String signatureHeader,
            String timestampHeader, String idHeader, String tolerance, String actor) {
        overlayEditor.writeWebhookVerifier(name, secretRef, signatureHeader, timestampHeader,
                idHeader, tolerance, actor);
    }

    /**
     * Adds or replaces an outbound/poll connector credential (Track J2). Secret-carrying fields
     * (bearer token, basic password, header value) must be secret references; the username and
     * header name ride plain. Applies on the next start.
     */
    public void writeConnectorCredential(String scope, String name, String type, String token,
            String username, String password, String header, String value, String actor) {
        overlayEditor.writeConnectorCredential(scope, name, type, token, username, password,
                header, value, actor);
    }

    /**
     * The connectors read model (Track J2): egress allow-lists, outbound/poll credentials and
     * webhook verifiers from the FRESH merged config (a just-saved overlay write shows without a
     * Studio reload). Secret-ish values are shown as their reference with any literal fallback
     * elided; a non-reference literal is never echoed.
     */
    public Map<String, Object> connectorsView() {
        return overlayEditor.connectorsView();
    }

    /**
     * A displayable form of a secret-carrying config value: a pure reference shows as-is, a
     * reference with a literal fallback elides the fallback, anything else is masked entirely.
     */
    static String redactedReference(String value) {
        return OverlayEditor.redactedReference(value);
    }

    /** The next versioned-migration number for a datasource/vendor (the Flyway {@code V<n>} prefix). */
    public String nextMigrationVersion(String datasource, String vendor) {
        return nextVersion(migrationDir(identifier(datasource, "main"), normalVendor(vendor)));
    }

    /**
     * Creates a new Flyway migration under {@code db/…/migration[-vendor]} (Studio backlog: migration
     * authoring). A versioned migration ({@code repeatable == false}) is auto-numbered {@code V<n>}
     * from the existing files (plain sequential, no zero-padding — the framework orders versions
     * numerically); a repeatable one is {@code R__<slug>}. The DDL is written verbatim (a placeholder
     * when blank). Gated by the read-only master switch and recorded to the audit trail; apply it with
     * the migration page's Migrate now (roadmap Phase 42) or the next start's migrate. Refuses to
     * overwrite an existing file unless {@code force}.
     */
    public MigrationResult createMigration(String datasource, String vendor, boolean repeatable,
            String description, String ddl, boolean force, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY,
                    "Studio is read-only; creating migrations is disabled");
        }
        String dir = migrationDir(identifier(datasource, "main"), normalVendor(vendor));
        String version = repeatable ? null : nextVersion(dir);
        String slug = slug(description);
        String filename = repeatable ? "R__" + slug + ".sql" : "V" + version + "__" + slug + ".sql";
        String relativePath = dir + "/" + filename;
        Path target = resolve(relativePath);
        if (Files.isRegularFile(target) && !force) {
            throw new TqlException(CONFLICT, "A migration already exists at " + relativePath
                    + (repeatable
                            ? "; open it in the editor, or use a different description."
                            : "; refresh the page and retry."));
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, migrationBody(ddl, repeatable));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "migration", relativePath);
        return new MigrationResult(relativePath, filename, version, repeatable);
    }

    /** The app-relative migration directory for a datasource and optional vendor overlay. */
    private static String migrationDir(String datasource, String vendor) {
        String base = "main".equals(datasource) ? "db" : "db/" + datasource;
        return base + "/" + (vendor == null ? "migration" : "migration-" + vendor);
    }

    /** The next {@code V<n>} for a migration directory: one past the highest existing version. */
    private String nextVersion(String dirRelative) {
        Path dir = appHome.resolve(dirRelative).normalize();
        int max = 0;
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : (Iterable<Path>) files::iterator) {
                    MigrationFile parsed = MigrationFile.parse("main", null, file);
                    if (parsed != null && parsed.version() != null) {
                        Matcher matcher = LEADING_DIGITS.matcher(parsed.version());
                        if (matcher.find()) {
                            max = Math.max(max, Integer.parseInt(matcher.group()));
                        }
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }
        return String.valueOf(max + 1);
    }

    /** Validates a datasource/vendor identifier, defaulting a blank one to {@code fallback}. */
    private static String identifier(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        if (!IDENTIFIER.matcher(trimmed).matches()) {
            throw new TqlException(NEW_ROUTE, "Invalid datasource/vendor name: " + value);
        }
        return trimmed;
    }

    private static String normalVendor(String vendor) {
        return vendor == null || vendor.isBlank() ? null : identifier(vendor, null);
    }

    /** Slugs a description into the Flyway {@code __<description>} segment (underscore-separated). */
    private static String slug(String description) {
        String slug = description == null
                ? ""
                : description.strip().replaceAll("[^\\p{L}\\p{N}]+", "_")
                        .replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) {
            throw new TqlException(NEW_ROUTE, "A migration needs a description");
        }
        return slug;
    }

    /** The migration file body: the supplied DDL, or a kind-appropriate placeholder when blank. */
    private static String migrationBody(String ddl, boolean repeatable) {
        String body = ddl == null ? "" : ddl.strip();
        if (body.isEmpty()) {
            body = repeatable
                    ? "-- Repeatable migration: redefine idempotently, e.g. CREATE OR REPLACE VIEW ..."
                    : "-- TODO: write the migration DDL.";
        }
        return body + "\n";
    }

    /**
     * The outcome of creating a migration: its app-relative path, filename, the assigned version
     * ({@code null} for a repeatable migration), and whether it is repeatable.
     */
    public record MigrationResult(String path, String filename, String version,
            boolean repeatable) {
    }

    /** Whether {@code path} is a Flyway migration under a {@code db/…/migration[-vendor]} location. */
    public static boolean isMigrationPath(String path) {
        return path != null && MIGRATION_PATH.matcher(path).matches();
    }

    /**
     * Dry-runs a migration's DDL against the dev datasource without persisting it (Studio backlog:
     * migration authoring). Studio stays database-free — the runtime supplies the execution over the
     * sandboxed (auto-rollback) datasource via a {@link DdlDryRun} callback.
     */
    @FunctionalInterface
    public interface DdlDryRun {
        /** Executes {@code ddl} in an auto-rollback sandbox and reports whether it applied cleanly. */
        DryRunResult run(String ddl);
    }

    /**
     * The outcome of a migration dry-run: whether it actually ran ({@code false} when declined — e.g.
     * a non-Postgres dialect whose DDL can't be rolled back), whether the DDL applied cleanly, and a
     * human-readable message.
     */
    public record DryRunResult(boolean ran, boolean ok, String message) {

        /** The DDL applied cleanly in the sandbox and was rolled back. */
        public static DryRunResult applied() {
            return new DryRunResult(true, true,
                    "Applies cleanly (rolled back — nothing persisted).");
        }

        /** The DDL ran but failed with {@code message}. */
        public static DryRunResult failed(String message) {
            return new DryRunResult(true, false, message);
        }

        /** The dry-run was declined (e.g. an unsupported dialect or no DDL), with the reason. */
        public static DryRunResult declined(String message) {
            return new DryRunResult(false, false, message);
        }
    }

    /**
     * Dry-runs the DDL of the migration at {@code relativePath} — the supplied {@code content} when
     * present (the live editor buffer), otherwise the saved file — against the sandbox via
     * {@code dryRun}, returning the outcome. Declines a non-migration path. It never persists, so it
     * is safe regardless of the read-only switch; the caller gates it like the test runner.
     */
    public DryRunResult dryRunMigration(String relativePath, String content, DdlDryRun dryRun) {
        if (!isMigrationPath(relativePath)) {
            return DryRunResult
                    .declined("Dry-run applies to migration files (db/…/migration/*.sql).");
        }
        String ddl = content != null ? content : sourceIfExists(relativePath);
        return dryRun.run(ddl);
    }

    /** The starter route skeleton for a recipe: a parseable draft the author then completes. */
    private static String starterRoute(String path, String recipe) {
        String id = path.substring("web/".length(), path.length() - ".yml".length())
                .replace('/', '.');
        return switch (recipe) {
            case "query-html" ->
                """
                        version: tesseraql/v1
                        id: %s
                        kind: route
                        recipe: query-html

                        security:
                          auth: bearer

                        sources:
                          main:
                            sql:
                              file: query.sql
                              mode: query

                        response:
                          html:
                            status: 200
                            template: page.html
                            model:
                              rows: main.rows
                            headers:
                              Content-Security-Policy: "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'"
                              X-Content-Type-Options: nosniff
                              X-Frame-Options: DENY
                              Referrer-Policy: no-referrer
                        """
                        .formatted(id);
            case "command-json" -> """
                    version: tesseraql/v1
                    id: %s
                    kind: route
                    recipe: command-json

                    security:
                      auth: bearer

                    steps:
                      - id: main
                        sql:
                          file: command.sql
                          mode: update

                    response:
                      json:
                        status: 200
                        body:
                          affected: steps.main.affectedRows
                    """.formatted(id);
            default -> """
                    version: tesseraql/v1
                    id: %s
                    kind: route
                    recipe: query-json

                    security:
                      auth: bearer

                    sources:
                      main:
                        sql:
                          file: query.sql
                          mode: query

                    response:
                      json:
                        status: 200
                        body:
                          data: main.rows
                    """.formatted(id);
        };
    }

    /** The disposition an apply would give a generated file, by reading its on-disk counterpart. */
    private String scaffoldStatus(ScaffoldedFile file) {
        Path target = resolve(file.path());
        if (!Files.isRegularFile(target)) {
            return "new";
        }
        String existing;
        try {
            existing = Files.readString(target);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        if (existing.equals(file.stampedContent())) {
            return "unchanged";
        }
        return ScaffoldChecksum.status(existing) == ScaffoldChecksum.Status.PRISTINE
                ? "regenerate"
                : "conflict";
    }

    static boolean isTemplate(String relativePath) {
        return relativePath.endsWith(".html") || relativePath.endsWith(".tpl");
    }

    /** Whether the path is a web route document ({@code web/**}/{@code <method>.yml}). */
    static boolean isRouteYaml(String relativePath) {
        if (!relativePath.startsWith("web/") || !relativePath.endsWith(".yml")) {
            return false;
        }
        int slash = relativePath.lastIndexOf('/');
        String stem = relativePath.substring(slash + 1, relativePath.length() - ".yml".length());
        return HTTP_METHODS.contains(stem);
    }

    static String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    /**
     * Promotes a saved draft to the source of truth after validating it compiles (design ch. 16.7).
     * Rejected in read-only mode; the draft is removed once applied.
     */
    public Path applyDraft(String relativePath) {
        return applyDraft(relativePath, false, null);
    }

    public Path applyDraft(String relativePath, boolean force) {
        return applyDraft(relativePath, force, null);
    }

    /**
     * As {@link #applyDraft(String)}, but {@code force} overwrites a source that changed since the
     * draft was started (Studio backlog D5), and {@code actor} (the caller, for the audit trail,
     * Studio backlog D6) is recorded once the draft is promoted. Without {@code force}, a
     * concurrent-edit conflict is rejected so the draft cannot silently clobber another change
     * (last-apply-wins).
     */
    public Path applyDraft(String relativePath, boolean force, String actor) {
        return draftStore.applyDraft(relativePath, force, actor);
    }

    /**
     * Re-reads the manifest from disk so the explorer and source views reflect applied changes
     * (design ch. 16.8). Returns the refreshed explorer.
     */
    public Explorer reload() {
        // Tolerant of unparseable route documents (they surface through the hot reloader's
        // failure report; the explorer keeps showing everything that still parses).
        this.manifest = new ManifestLoader().load(appHome, new java.util.ArrayList<>());
        this.appHome = manifest.appHome();
        return explorer();
    }

    /**
     * Every pending draft under {@code work/studio/drafts} (Studio backlog D5): the app-relative path
     * each one edits, whether it conflicts with a source that changed underneath it, and whether it is
     * a new file (no source yet). Sorted by path; the base sidecars are skipped.
     */
    public List<DraftSummary> drafts() {
        return draftStore.drafts();
    }

    /** The outcome of applying every clean pending draft at once (Studio Drafts bulk actions). */
    public record BulkApplyResult(int applied, int skipped, boolean needsRestart) {
    }

    /**
     * Applies every pending draft that does not conflict, recording each to the audit trail as
     * {@code actor} (Studio Drafts bulk actions). Conflicting drafts are left untouched (skipped) —
     * they need a manual diff review in the editor — and counted. {@code needsRestart} is true when
     * any applied draft created a not-yet-served route file. Callers reload routes afterwards.
     */
    public BulkApplyResult applyAllDrafts(String actor) {
        return draftStore.applyAllDrafts(actor);
    }

    /** Discards every pending draft, returning how many were removed (Studio Drafts bulk actions). */
    public int discardAllDrafts() {
        return draftStore.discardAllDrafts();
    }

    /**
     * The audit trail (Studio backlog D6): who applied or scaffolded what, when — newest first, up to
     * {@code limit} entries. The trail is the append-only {@code work/studio/audit/audit.jsonl} log
     * the source-writing operations stamp; an empty list when the log is absent.
     */
    public List<AuditEntry> auditEntries(int limit) {
        return auditEntries(limit, null);
    }

    /**
     * Records a Studio-triggered migrate run to the audit trail (roadmap Phase 42): the runtime
     * owns the datasources and runs the migration, Studio owns the trail.
     */
    public void recordMigrationRun(String actor, String target) {
        recordAudit(actor, "migrate", target);
    }

    /**
     * Records a data-browser row edit to the audit trail (Track J4): the row identity and the
     * columns touched — never the values.
     */
    public void recordDataEdit(String actor, String target) {
        recordAudit(actor, "data", target);
    }

    /**
     * Records a copilot-saved draft to the audit trail (roadmap Phase 44): the model proposes,
     * the trail names the chatting user, and the draft still needs that human's apply.
     */
    public void recordCopilotDraft(String actor, String target) {
        recordAudit(actor, "copilot", target);
    }

    /**
     * Writes the introspected schema sidecar (docs/studio-schema-lifecycle.md): the caller
     * introspected the runtime's own datasources, this persists the same {@code SchemaOverlay}
     * envelope the build-time {@code schema} goal emits, confined and audited like every
     * Studio write.
     */
    public void refreshSchema(
            java.util.Map<String, io.tesseraql.yaml.scaffold.CatalogSchema> datasources,
            String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY,
                    "Studio is read-only; refreshing the schema is disabled");
        }
        Path target = resolve(DocService.SCHEMA_PATH);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, jsonMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new SchemaOverlay(1,
                            java.time.Instant.now().toString(), datasources)));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "schema-refresh", DocService.SCHEMA_PATH);
    }

    /**
     * Captures both diff baselines in one action (docs/studio-schema-lifecycle.md):
     * {@code schema.baseline.json} as a copy of the current schema sidecar, and
     * {@code openapi.baseline.json} from the live OpenAPI document the caller rendered —
     * no intermediate {@code openapi.json} file exists at runtime.
     */
    public void captureBaselines(String openApiJson, String actor) {
        if (readOnly) {
            throw new TqlException(READ_ONLY,
                    "Studio is read-only; capturing baselines is disabled");
        }
        Path schema = resolve(DocService.SCHEMA_PATH);
        if (!Files.isRegularFile(schema)) {
            throw new TqlException(NO_SCHEMA,
                    "No schema sidecar to baseline; refresh the schema first.");
        }
        try {
            Files.copy(schema, resolve(DocService.SCHEMA_BASELINE_PATH),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(resolve(DocService.OPENAPI_BASELINE_PATH), openApiJson);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        recordAudit(actor, "baseline-capture", ".tesseraql/docs");
    }

    /**
     * The newest {@code limit} audit entries matching {@code query} (Studio platform-UX H5). The
     * filter runs over the <em>whole</em> log before the limit applies, so a search reaches older
     * actions, not just the newest window; an empty query returns the newest {@code limit} entries.
     */
    public List<AuditEntry> auditEntries(int limit, String query) {
        return auditTrail.entries(limit, query);
    }

    /** The named decision as a rectangular grid, read the way the editor reads it. */
    public DecisionGrid decisionGrid(String name) {
        return decisionForms.decisionGrid(name);
    }

    /** Writes the edited decision rows back as a draft (Studio decision-table editor). */
    public Path saveDecisionRows(String name, List<DecisionColumn> columns,
            java.util.Set<Integer> deletes, String actor) {
        return decisionForms.saveDecisionRows(name, columns, deletes, actor);
    }

    /** Every decision a document declares, for the pickers that reference one. */
    public List<SharedDecision> sharedDecisions() {
        return decisionForms.sharedDecisions();
    }

    /** Every calendar the app declares, with where it came from. */
    public List<CalendarSummary> calendars() {
        return calendarForms.calendars();
    }

    /** One calendar month as the grid the reader clicks (Studio calendars form). */
    public CalendarMonthGrid calendarMonth(String name, String month, Integer dayOfMonth,
            String shift, java.util.Set<java.time.LocalDate> tableHolidays) {
        return calendarForms.calendarMonth(name, month, dayOfMonth, shift, tableHolidays);
    }

    /** Writes the edited calendar back as a draft. */
    public Path saveCalendar(String name, List<String> weekend, List<String> dates, String actor) {
        return calendarForms.saveCalendar(name, weekend, dates, actor);
    }

    /** What the calendar form shows as currently declared. */
    public CalendarEditState calendarEditState(String name) {
        return calendarForms.calendarEditState(name);
    }

    /** Adds or removes one holiday date, the click the month grid makes. */
    public Path toggleCalendarHoliday(String name, String date, String actor) {
        return calendarForms.toggleCalendarHoliday(name, date, actor);
    }

    /** A declared job's trigger and operational promises, as a form (docs/jobs.md). */
    public JobPolicyForm jobPolicyForm(String jobId) {
        return jobPolicyForms.jobPolicyForm(jobId);
    }

    /** Writes the edited job policies back as a draft. */
    public Path saveJobPolicies(String jobId, String cron, String fixedDelay, String calendar,
            String runOn, String dayOfMonth, String shift, String after, String overlap,
            String slaWithin, String slaBy, String actor) {
        return jobPolicyForms.saveJobPolicies(jobId, cron, fixedDelay, calendar, runOn, dayOfMonth,
                shift, after, overlap, slaWithin, slaBy, actor);
    }

    /** The structured form model for a route document (Track J1). */
    public RouteForm routeForm(String relativePath) {
        return routeForms.routeForm(relativePath);
    }

    /** Applies the structured form onto the route document and saves it as a draft (Track J1). */
    public Path routeFormSave(String relativePath, String recipe, String auth, String policy,
            String csrf, List<FormInput> inputs) {
        return routeForms.routeFormSave(relativePath, recipe, auth, policy, csrf, inputs);
    }

    /** The sortable columns of the audit trail (Studio platform-UX I2). */
    public static final List<String> AUDIT_SORT_COLS = AuditTrail.SORT_COLUMNS;

    public AuditPage auditPage(String query, int page, int size) {
        return auditPage(query, null, null, page, size);
    }

    /**
     * One page of the audit trail matching {@code query} (Studio platform-UX I3 + I2 sort): the whole
     * log is filtered, then sorted by {@code sort} (one of {@link #AUDIT_SORT_COLS}; default {@code at}
     * newest-first), then sliced to the {@code page}-th 1-based page of {@code size}. The filtered
     * {@code total} comes back so the view can render pagination.
     */
    public AuditPage auditPage(String query, String sort, String dir, int page, int size) {
        return auditTrail.page(query, sort, dir, page, size);
    }

    /** The app's current declarative sidebar menu items ({@code config/menu.yml}); empty if none. */
    public List<MenuItem> menuItems() {
        return overlayEditor.menuItems();
    }

    /** Lints the app home for the Studio health dashboard (the same engine as the CLI/Maven lint). */
    public List<LintFinding> health() {
        return new AppLinter().lint(appHome);
    }

    /** Distinct roles named across the app's {@code tesseraql.security.policies} (menu autocomplete). */
    public List<String> knownRoles() {
        return policyValues("role");
    }

    /** Distinct permissions named across the app's security policies (menu autocomplete). */
    public List<String> knownPermissions() {
        return policyValues("permission");
    }

    /** Distinct {@code role}/{@code permission} values across every policy's {@code anyOf} rules. */
    private List<String> policyValues(String key) {
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        if (!(policies instanceof Map<?, ?> byId)) {
            return List.of();
        }
        java.util.TreeSet<String> values = new java.util.TreeSet<>();
        for (Object policy : byId.values()) {
            if (policy instanceof Map<?, ?> spec && spec.get("anyOf") instanceof List<?> rules) {
                for (Object rule : rules) {
                    if (rule instanceof Map<?, ?> r && r.get(key) != null) {
                        String value = String.valueOf(r.get(key)).strip();
                        if (!value.isEmpty()) {
                            values.add(value);
                        }
                    }
                }
            }
        }
        return List.copyOf(values);
    }

    /** The distinct HTTP paths the app serves — href autocomplete + the dangling-href check. */
    public List<String> routePaths() {
        java.util.TreeSet<String> paths = new java.util.TreeSet<>();
        for (RouteFile route : manifest.routes()) {
            if (route.urlPath() != null && !route.urlPath().isBlank()) {
                paths.add(route.urlPath());
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Prefill values for the API console when it is deep-linked with {@code ?path=&method=} (e.g. from
     * a route's docs page): the resolved method + path, plus a skeleton built from the matched route's
     * declared inputs — a JSON body for a body method, or a query string for a read method.
     */
    public Map<String, Object> tryPrefill(String method, String path) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (path == null || path.isBlank()) {
            return out;
        }
        out.put("path", path);
        String wanted = method == null ? null : method.strip();
        RouteFile match = null;
        for (RouteFile route : manifest.routes()) {
            if (path.equals(route.urlPath())
                    && (wanted == null || wanted.equalsIgnoreCase(route.httpMethod()))) {
                match = route;
                break;
            }
        }
        if (match == null) {
            if (wanted != null) {
                out.put("method", wanted.toUpperCase(java.util.Locale.ROOT));
            }
            return out;
        }
        String httpMethod = match.httpMethod().toUpperCase(java.util.Locale.ROOT);
        out.put("method", httpMethod);
        java.util.LinkedHashMap<String, Object> writable = new java.util.LinkedHashMap<>();
        match.definition().input().forEach((name, field) -> {
            if (field.isWritable()) {
                writable.put(name, inputExample(field.type()));
            }
        });
        if (writable.isEmpty()) {
            return out;
        }
        boolean bodyMethod = !("GET".equals(httpMethod) || "HEAD".equals(httpMethod)
                || "DELETE".equals(httpMethod) || "OPTIONS".equals(httpMethod));
        if (bodyMethod) {
            try {
                out.put("body", jsonMapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(writable));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Skip the body skeleton if it can't be serialized (never expected for scalars).
            }
        } else {
            out.put("query", writable.keySet().stream()
                    .map(name -> name + "=").collect(java.util.stream.Collectors.joining("&")));
        }
        return out;
    }

    /** A placeholder example value for an input type, used in the API-console body skeleton. */
    private static Object inputExample(String type) {
        return switch (type == null ? "string" : type) {
            case "integer", "number" -> 0;
            case "boolean" -> false;
            default -> "";
        };
    }

    /** The app's message-catalog locale tags ({@code messages/<tag>.yml}), tag-sorted. */
    public List<String> messageLocales() {
        return overlayEditor.messageLocales();
    }

    /** Each locale's flat key→value message entries (dotted keys), for the i18n editor table. */
    public Map<String, Map<String, String>> messageCatalogs() {
        return overlayEditor.messageCatalogs();
    }

    /**
     * Upserts a translation into {@code messages/<locale>.yml} — the dotted {@code key} is written
     * into the nested map, other keys preserved, creating the file/locale if new. Edit-gated and
     * audited; the message resolver reads the catalog live, so the change is served immediately.
     */
    public void setMessage(String locale, String key, String value, String actor) {
        overlayEditor.setMessage(locale, key, value, actor);
    }

    /**
     * The app's effective (merged) configuration flattened to dotted-key rows for the Studio config
     * viewer, sorted by key. Values are shown unresolved (so {@code ${ENV}} references stay visible,
     * not their secret values); a value whose key names a secret is redacted unless it is such a
     * reference.
     */
    public List<Map<String, Object>> effectiveConfig() {
        return overlayEditor.effectiveConfig();
    }

    /** The curated editable settings with their current effective values, for the config editor. */
    public List<Map<String, Object>> editableSettings() {
        return overlayEditor.editableSettings();
    }

    /**
     * Overrides a curated setting in {@code config/overlay.yml} (the base config untouched), or, when
     * {@code value} is blank, removes the override. Only whitelisted keys are accepted. Edit-gated and
     * audited; applied on the next restart (the setting is read at startup).
     */
    public void setConfigValue(String key, String value, String actor) {
        overlayEditor.setConfigValue(key, value, actor);
    }

    /** The app's live feature flags ({@code config/flags.yml}) — name to (typed) value. */
    public Map<String, Object> flags() {
        return overlayEditor.flags();
    }

    /**
     * Sets (or adds) a feature flag in {@code config/flags.yml}, coercing the value by {@code type}
     * ({@code boolean}/{@code number}/{@code string}). Edit-gated and audited; served live (the
     * request binder reads flags live), so the change takes effect on the next request.
     */
    public void setFlag(String name, String value, String type, String actor) {
        overlayEditor.setFlag(name, value, type, actor);
    }

    /** Removes a feature flag; a no-op when it is not set. */
    public void removeFlag(String name, String actor) {
        overlayEditor.removeFlag(name, actor);
    }

    /**
     * Every route's security posture for the Studio security overview: its auth type, policy, source,
     * and the two governance flags — {@code unprotected} (no auth declared) and {@code csrfGap} (a
     * state-changing browser route without CSRF).
     */
    public List<Map<String, Object>> routeSecurity() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RouteFile route : manifest.routes()) {
            io.tesseraql.yaml.model.SecuritySpec security = route.definition().security();
            String auth = security == null ? null : security.auth();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", route.definition().id());
            row.put("method", route.httpMethod());
            row.put("path", route.urlPath());
            row.put("auth", auth);
            row.put("policy", security == null ? null : security.policy());
            row.put("source", relative(route.source()));
            row.put("unprotected", auth == null || auth.isBlank());
            boolean stateChanging = !"GET".equalsIgnoreCase(route.httpMethod())
                    && !"HEAD".equalsIgnoreCase(route.httpMethod())
                    && !"OPTIONS".equalsIgnoreCase(route.httpMethod());
            boolean csrfOn = security != null && security.csrfEnforced(route.httpMethod());
            row.put("csrfGap", "browser".equals(auth) && stateChanging && !csrfOn);
            out.add(row);
        }
        return out;
    }

    /**
     * The app's authorization policies ({@code tesseraql.security.policies}), each with a readable
     * summary of its {@code anyOf} rules (roles / permissions / claims), sorted by id.
     */
    public List<Map<String, Object>> securityPolicies() {
        Object policies = manifest.config().navigate("tesseraql.security.policies");
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(policies instanceof Map<?, ?> byId)) {
            return out;
        }
        java.util.TreeMap<String, Object> sorted = new java.util.TreeMap<>();
        byId.forEach((id, spec) -> sorted.put(String.valueOf(id), spec));
        sorted.forEach((id, spec) -> {
            List<String> tokens = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            if (spec instanceof Map<?, ?> map && map.get("anyOf") instanceof List<?> rules) {
                for (Object rule : rules) {
                    if (rule instanceof Map<?, ?> r) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        if (r.get("role") != null) {
                            row.put("kind", "role");
                            row.put("value", String.valueOf(r.get("role")));
                        } else if (r.get("permission") != null) {
                            row.put("kind", "permission");
                            row.put("value", String.valueOf(r.get("permission")));
                        } else if (r.get("claimName") != null) {
                            row.put("kind", "claim");
                            row.put("value", r.get("claimName") + "=" + r.get("claimValue"));
                        } else {
                            continue;
                        }
                        row.put("label", row.get("kind") + " " + row.get("value"));
                        // Claims are shown but not individually removable from the editor (only role/
                        // permission rules are edited here).
                        row.put("editable", !"claim".equals(row.get("kind")));
                        tokens.add(String.valueOf(row.get("label")));
                        rows.add(row);
                    }
                }
            }
            Map<String, Object> policy = new LinkedHashMap<>();
            policy.put("id", id);
            policy.put("rules", rows);
            policy.put("summary", tokens.isEmpty() ? "(no rules)" : String.join(" OR ", tokens));
            out.add(policy);
        });
        return out;
    }

    /**
     * Grants a policy an extra {@code role} or {@code permission} rule by writing the policy's full
     * rule set to {@code config/overlay.yml} (the last-merged overlay, so the base config is left
     * intact). A previously undefined policy is created. Edit-gated and audited; the caller reloads
     * the security engine so the change is live.
     */
    public void addPolicyRule(String policyId, String kind, String value, String actor) {
        overlayEditor.addPolicyRule(policyId, kind, value, actor);
    }

    /**
     * Revokes a {@code role}/{@code permission} rule from a policy by writing the reduced rule set to
     * {@code config/overlay.yml} (which overrides the base). Removing the last rule leaves a policy
     * that grants no one (deny-by-default). A base-only policy cannot be deleted via the overlay.
     */
    public void removePolicyRule(String policyId, String kind, String value, String actor) {
        overlayEditor.removePolicyRule(policyId, kind, value, actor);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mutableCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key,
                value instanceof Map ? mutableCopy((Map<String, Object>) value) : value));
        return copy;
    }

    /** Returns {@code parent}'s child map at {@code key}, creating a mutable one when absent. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object child = parent.get(key);
        if (child instanceof Map) {
            Map<String, Object> mutable = mutableCopy((Map<String, Object>) child);
            parent.put(key, mutable);
            return mutable;
        }
        Map<String, Object> created = new LinkedHashMap<>();
        parent.put(key, created);
        return created;
    }

    /**
     * Appends a menu item to {@code config/menu.yml} and records it to the audit trail. {@code label}
     * and {@code href} are required; {@code icon} is an optional sprite id; {@code rolesCsv}/
     * {@code permsCsv} are comma-separated visibility lists (empty ⇒ a public item).
     */
    public void addMenuItem(String label, String href, String icon, String rolesCsv,
            String permsCsv, String actor) {
        overlayEditor.addMenuItem(label, href, icon, rolesCsv, permsCsv, actor);
    }

    /**
     * Removes the menu item at {@code index} and records the change.
     *
     * <p>An index outside the list is refused rather than ignored: the handler answered
     * {@code {"removed": true}} for it, so a malformed or stale index reported a change that
     * never happened and left no audit record to contradict it (docs/silent-tolerance.md O10).
     */
    public void removeMenuItem(int index, String actor) {
        overlayEditor.removeMenuItem(index, actor);
    }

    /**
     * Moves the menu item at {@code index} one slot up ({@code delta < 0}) or down
     * ({@code delta > 0}). Moving the first item up, or the last down, is a legitimate no-op —
     * the item is already where it was asked to go — but an index outside the list is refused.
     */
    public void moveMenuItem(int index, int delta, String actor) {
        overlayEditor.moveMenuItem(index, delta, actor);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Splits a comma-separated field into a trimmed, blank-free list. */
    static List<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::strip)
                .filter(part -> !part.isEmpty()).toList();
    }

    /** Appends one audit entry for a source-writing action (Studio backlog D6). */
    private void recordAudit(String actor, String action, String target) {
        auditTrail.record(actor, action, target);
    }

    /** Reads a previously saved draft, or null if none exists. */
    public String readDraft(String relativePath) {
        return draftStore.readDraft(relativePath);
    }

    /**
     * The {@code vscode://file/...} deep link for a source file — the Studio-to-editor half of the
     * boundary's round trip (docs/vscode-extension.md, Phase 57). Best-effort by design: the
     * absolute path is the server's, so the link works when the browser and the files share a
     * machine (the normal dev loop) and stays inert otherwise.
     */
    public String editorHref(String relativePath) {
        String absolute = resolve(relativePath).toString().replace('\\', '/');
        StringBuilder encoded = new StringBuilder("vscode://file");
        if (!absolute.startsWith("/")) {
            encoded.append('/');
        }
        for (int i = 0; i < absolute.length(); i++) {
            char c = absolute.charAt(i);
            if (c == '/' || Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_'
                    || c == '~') {
                encoded.append(c);
            } else {
                for (byte b : String.valueOf(c).getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    encoded.append('%').append(String.format("%02X", b));
                }
            }
        }
        return encoded.toString();
    }

    private Path resolve(String relativePath) {
        return draftStore.resolve(relativePath);
    }

    private RouteSummary routeSummary(RouteFile route) {
        return new RouteSummary(route.definition().id(), route.httpMethod(), route.urlPath(),
                route.definition().recipe(), relative(route.source()));
    }

    private JobSummary jobSummary(JobFile job) {
        return new JobSummary(job.definition().id(), job.definition().recipe(),
                relative(job.source()));
    }

    private String relative(Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /**
     * The explorer model: the app, its routes and jobs, and the pending drafts (Studio sidebar IA):
     * the tree marks a served entry that has an unsaved draft and surfaces a new (not-yet-served)
     * draft as its own pending node, so "what I am authoring" is visible alongside "what is served".
     */
    public record Explorer(String appName, boolean readOnly,
            List<RouteSummary> routes, List<JobSummary> jobs, List<DraftSummary> drafts) {
    }

    /** A route entry in the explorer. */
    public record RouteSummary(String id, String method, String path, String recipe,
            String source) {
    }

    /** A job entry in the explorer. */
    public record JobSummary(String id, String recipe, String source) {
    }

    /**
     * A pending draft in the draft overview (Studio backlog D5): the app-relative path it edits,
     * whether it conflicts with a source that changed underneath it, and whether it is a new file.
     */
    public record DraftSummary(String path, boolean conflict, boolean isNew) {
    }

    /**
     * One audit-trail entry (Studio backlog D6): when a source-writing action happened ({@code at},
     * an ISO-8601 instant), who did it ({@code actor}), the {@code action} ({@code apply}/{@code
     * scaffold}), and the {@code target} (the applied path or the scaffolded table).
     */
    public record AuditEntry(String at, String actor, String action, String target) {
    }

    /** One page of the audit trail: its entries plus the page coordinates and filtered total (I3). */
    public record AuditPage(List<AuditEntry> entries, int page, int size, int total) {
    }

    /**
     * The preview of a table's scaffolded CRUD slice (Studio backlog B3): every generated file with
     * its content and apply disposition, plus the counts a confirmation step shows — how many files
     * an apply would write and how many it would skip as conflicts.
     */
    public record ScaffoldPreview(String table, List<ScaffoldFile> files, int total,
            int writeCount, int conflictCount) {

        public ScaffoldPreview {
            files = List.copyOf(files);
        }
    }

    /**
     * One previewed scaffold file: its app-home-relative path, generated content (before checksum
     * stamping), and the {@code status} an apply would give it ({@code new}/{@code unchanged}/
     * {@code regenerate}/{@code conflict}).
     */
    public record ScaffoldFile(String path, String content, String status) {
    }

    /**
     * The outcome of a scaffold apply (Studio backlog B3): the files written, left unchanged, and
     * skipped (edited/owned), the subset of written files that are newly added routes needing a
     * restart to be served, and whether any file was held back ({@code blocked}).
     */
    public record ScaffoldResult(String table, List<String> written, List<String> unchanged,
            List<String> skipped, List<String> newRoutes, boolean blocked) {

        public ScaffoldResult {
            written = List.copyOf(written);
            unchanged = List.copyOf(unchanged);
            skipped = List.copyOf(skipped);
            newRoutes = List.copyOf(newRoutes);
        }
    }

    /** The outcome of a preview/validation: whether it compiled, and the result or error detail. */
    public record PreviewResult(boolean valid, String kind, String result, String error) {

        static PreviewResult valid(String kind, String result) {
            return new PreviewResult(true, kind, result, null);
        }

        static PreviewResult invalid(String kind, String error) {
            return new PreviewResult(false, kind, null, error);
        }
    }

    /**
     * The outcome of a rendered preview (Studio backlog A1): whether the template rendered against
     * the sample model, the {@code kind} of output ({@code html}/{@code text}, or {@code sample}
     * when the sample data itself was malformed), and either the rendered {@code output} or the
     * {@code error} detail.
     */
    public record RenderResult(boolean ok, String kind, String output, String error) {

        static RenderResult ok(String kind, String output) {
            return new RenderResult(true, kind, output, null);
        }

        static RenderResult invalid(String kind, String error) {
            return new RenderResult(false, kind, null, error);
        }
    }
}
