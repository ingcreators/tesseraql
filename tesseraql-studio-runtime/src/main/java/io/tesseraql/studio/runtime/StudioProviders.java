package io.tesseraql.studio.runtime;

import static io.tesseraql.studio.runtime.StudioSupport.DATA_EDIT_SLOTS;
import static io.tesseraql.studio.runtime.StudioSupport.LOG;
import static io.tesseraql.studio.runtime.StudioSupport.MENU_ICON_OPTIONS;
import static io.tesseraql.studio.runtime.StudioSupport.ROUTE_FORM_INPUT_SLOTS;
import static io.tesseraql.studio.runtime.StudioSupport.ROW_EDIT_REJECTED;
import static io.tesseraql.studio.runtime.StudioSupport.actorOf;
import static io.tesseraql.studio.runtime.StudioSupport.dataCombinator;
import static io.tesseraql.studio.runtime.StudioSupport.dataFilterSlots;
import static io.tesseraql.studio.runtime.StudioSupport.dataFilters;
import static io.tesseraql.studio.runtime.StudioSupport.dataQueryBase;
import static io.tesseraql.studio.runtime.StudioSupport.dataRowKey;
import static io.tesseraql.studio.runtime.StudioSupport.dataSortDir;
import static io.tesseraql.studio.runtime.StudioSupport.menuIndex;
import static io.tesseraql.studio.runtime.StudioSupport.menuVisibility;
import static io.tesseraql.studio.runtime.StudioSupport.parseIndex;
import static io.tesseraql.studio.runtime.StudioSupport.parseJsonObject;
import static io.tesseraql.studio.runtime.StudioSupport.parsePage;
import static io.tesseraql.studio.runtime.StudioSupport.parseQueryString;
import static io.tesseraql.studio.runtime.StudioSupport.putEditFlags;
import static io.tesseraql.studio.runtime.StudioSupport.putIfPresent;
import static io.tesseraql.studio.runtime.StudioSupport.rebindPolicyEngine;
import static io.tesseraql.studio.runtime.StudioSupport.requireCopilot;
import static io.tesseraql.studio.runtime.StudioSupport.requireExplicitConfirm;
import static io.tesseraql.studio.runtime.StudioSupport.requiredParam;
import static io.tesseraql.studio.runtime.StudioSupport.sourceEditorUrl;
import static io.tesseraql.studio.runtime.StudioSupport.str;
import static io.tesseraql.studio.runtime.StudioSupport.tryInvoke;
import static io.tesseraql.studio.runtime.StudioSupport.urlEncode;

import com.zaxxer.hikari.HikariDataSource;
import io.tesseraql.pipeline.RuntimeContext;
import io.tesseraql.runtime.AppMigrations;
import io.tesseraql.runtime.CalendarDecisions;
import io.tesseraql.runtime.RouteReloader;
import io.tesseraql.runtime.TenantDataSources;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The service providers backing the bundled Studio app (design ch. 16, 47), extracted verbatim
 * from the runtime boot. Registration order and lambda bodies are exactly what
 * {@code TesseraqlRuntime.start(...)} inlined before the extraction — this class only
 * relocates them.
 */
final class StudioProviders {

    private final io.tesseraql.studio.StudioService studio;
    private final StudioEdit studioEdit;
    private final StudioTestService studioTests;
    private final StudioScaffoldService studioScaffold;
    private final StudioDataService studioData;
    private final io.tesseraql.studio.CopilotService copilotService;
    private final io.tesseraql.studio.StudioService.FieldMask studioMask;
    private final io.tesseraql.studio.StudioService.PdfRender studioPdf;
    private final boolean scaffoldEnabled;
    private final boolean testRunnerEnabled;
    private final RouteReloader reloader;
    private final AppManifest manifest;
    private final Path appHome;
    private final String appName;
    /** Read at call time, not at registration: the seams bind before the server listens. */
    private final java.util.function.IntSupplier port;
    private final RuntimeContext context;
    private final HikariDataSource dataSource;
    private final Map<String, HikariDataSource> dataSources;
    private final TenantDataSources tenantDataSources;
    private final CalendarDecisions calendarDecisions;
    private final io.tesseraql.yaml.notify.NotificationChannels notificationChannels;
    private final StudioDocCache docCache;

    private StudioProviders(Deps deps) {
        this.studio = deps.studio();
        this.studioEdit = deps.studioEdit();
        this.studioTests = deps.studioTests();
        this.studioScaffold = deps.studioScaffold();
        this.studioData = deps.studioData();
        this.copilotService = deps.copilotService();
        this.studioMask = deps.studioMask();
        this.studioPdf = deps.studioPdf();
        this.scaffoldEnabled = deps.scaffoldEnabled();
        this.testRunnerEnabled = deps.testRunnerEnabled();
        this.reloader = deps.reloader();
        this.manifest = deps.manifest();
        this.appHome = deps.appHome();
        this.appName = deps.appName();
        this.port = deps.port();
        this.context = deps.context();
        this.dataSource = deps.dataSource();
        this.dataSources = deps.dataSources();
        this.tenantDataSources = deps.tenantDataSources();
        this.calendarDecisions = deps.calendarDecisions();
        this.notificationChannels = deps.notificationChannels();
        this.docCache = deps.docCache();
    }

    /**
     * The boot-time state the studio provider lambdas capture: every component is the
     * effectively-final local {@code TesseraqlRuntime.start(...)} built, captured by value
     * exactly as the inline lambdas did. Nothing here is reassigned by a route reload — the
     * providers that need post-reload freshness re-read from disk themselves (for example via
     * {@code new ManifestLoader().load(appHome)}).
     */
    record Deps(io.tesseraql.studio.StudioService studio, StudioEdit studioEdit,
            StudioTestService studioTests, StudioScaffoldService studioScaffold,
            StudioDataService studioData, io.tesseraql.studio.CopilotService copilotService,
            io.tesseraql.studio.StudioService.FieldMask studioMask,
            io.tesseraql.studio.StudioService.PdfRender studioPdf, boolean scaffoldEnabled,
            boolean testRunnerEnabled, RouteReloader reloader, AppManifest manifest,
            Path appHome, String appName, java.util.function.IntSupplier port,
            RuntimeContext context,
            HikariDataSource dataSource, Map<String, HikariDataSource> dataSources,
            TenantDataSources tenantDataSources, CalendarDecisions calendarDecisions,
            io.tesseraql.yaml.notify.NotificationChannels notificationChannels,
            StudioDocCache docCache) {
    }

    /**
     * Registers every {@code studio.*} provider on {@code serviceProviders}, in boot order.
     *
     * <p>The 85 registrations used to be one 1,900-line fluent statement; they are grouped by
     * feature now, order preserved exactly. The captured state moved from method locals to the
     * fields above so every lambda body stayed verbatim.
     */
    static void register(io.tesseraql.core.service.ServiceProviders serviceProviders, Deps deps) {
        StudioProviders providers = new StudioProviders(deps);
        providers.explorerAndSource(serviceProviders);
        providers.mailAndPageComposers(serviceProviders);
        providers.pagesViewsDraftsCopilot(serviceProviders);
        providers.routesAndMenu(serviceProviders);
        providers.healthSecurityPolicyTry(serviceProviders);
        providers.connectorsAndIdentityWizards(serviceProviders);
        providers.configAndFlags(serviceProviders);
        providers.dataBrowser(serviceProviders);
        providers.messagesEditor(serviceProviders);
        providers.snippetBuilders(serviceProviders);
        providers.decisionsCalendarsJobs(serviceProviders);
        providers.schemaAndMigrationRuns(serviceProviders);
        providers.applyPreviewTestScaffoldAudit(serviceProviders);
    }

    /** Explorer and source editing. */
    private void explorerAndSource(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.explorer", params -> {
                    Object query = params.get("q");
                    String q = query == null ? "" : String.valueOf(query);
                    Map<String, Object> model = io.tesseraql.studio.StudioViews
                            .explorer(studio.explorer(q));
                    // Edit affordances follow the caller's edit permission (backlog D6).
                    boolean canEdit = studioEdit.canEdit(params);
                    putEditFlags(model, canEdit);
                    // Offer the scaffold action only when B3 is on and the caller may edit.
                    model.put("scaffoldEnabled", scaffoldEnabled && canEdit);
                    // Echo the filter query (Studio backlog C4) so the input keeps its value.
                    model.put("query", q);
                    // The palette's "new route here" landing (studio-ux-refresh
                    // slice 7): ?create=<prefix>/ auto-opens the drawer, seeded.
                    Object create = params.get("create");
                    model.put("createPrefix", canEdit && create != null
                            ? String.valueOf(create)
                            : "");
                    return model;
                })
                // The command palette's dynamic groups (studio-ux-refresh slice 7):
                // every route/job as an open-in-editor entry, each route folder as a
                // "new route here" entry (edit-gated — creation is).
                .register("studio.command", params -> {
                    io.tesseraql.studio.StudioService.Explorer explorer = studio
                            .explorer("");
                    java.util.List<Map<String, Object>> entries = new java.util.ArrayList<>();
                    java.util.Set<String> folderPaths = new java.util.TreeSet<>();
                    for (io.tesseraql.studio.StudioService.RouteSummary route : explorer
                            .routes()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("label", route.method() + " " + route.path() + " · "
                                + route.id());
                        row.put("url", sourceEditorUrl(route.source()));
                        entries.add(row);
                        int slash = route.source().lastIndexOf('/');
                        if (slash > 0) {
                            folderPaths.add(route.source().substring(0, slash));
                        }
                    }
                    for (io.tesseraql.studio.StudioService.JobSummary job : explorer
                            .jobs()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("label", "job " + job.id());
                        row.put("url", sourceEditorUrl(job.source()));
                        entries.add(row);
                    }
                    java.util.List<Map<String, Object>> folders = new java.util.ArrayList<>();
                    for (String path : folderPaths) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("path", path);
                        row.put("url", "/_tesseraql/studio/ui?create="
                                + java.net.URLEncoder.encode(path + "/",
                                        java.nio.charset.StandardCharsets.UTF_8));
                        folders.add(row);
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("entries", entries);
                    model.put("folders", folders);
                    model.put("editable",
                            studioEdit.canEdit(params));
                    return model;
                })
                // The New-route drawer fragment (Studio sidebar IA): echoes the folder prefix a
                // "new route here" trigger passes so the form's Path seeds to the browsed
                // location. A tiny provider because response.html models resolve from `sql`.
                .register("studio.newForm", params -> {
                    Object prefix = params.get("prefix");
                    return Map.of("prefix", prefix == null ? "" : String.valueOf(prefix));
                })
                .register("studio.source", params -> {
                    String path = String.valueOf(params.get("path"));
                    EditorChrome chrome = EditorChrome.of(studio, studioEdit, path, params);
                    if (!chrome.hasDraft() && chrome.saved() == null) {
                        // Neither a draft nor a file: the source read raises the 404 the
                        // editor page answers with.
                        studio.source(path);
                    }
                    // The editor shows the draft when one exists (sourceContent is null for a
                    // new-file draft), otherwise the source it just read.
                    Map<String, Object> model = io.tesseraql.studio.StudioViews.source(path,
                            chrome.text(), !chrome.canEdit(), chrome.hasDraft(),
                            chrome.saved(), chrome.sampleModel());
                    // The Studio-to-editor half of the boundary's round trip (Phase 57).
                    model.put("editorHref", studio.editorHref(path));
                    // Offer the "run tests" action on a route page only when A2 is enabled.
                    model.put("testRunnerEnabled", testRunnerEnabled);
                    // The conflict warning, the confirm-before-apply gate and the edit
                    // permission the shared action bar renders from.
                    chrome.applyTo(model);
                    // The visual builder's entry (docs/page-builder.md D1): eligible
                    // page templates offer "Edit visually"; composable mail templates
                    // route to the mail composer instead.
                    Eligibility eligible = Eligibility.of(path, chrome.text());
                    model.put("builderEligible", eligible.builder());
                    model.put("mailComposable", eligible.mailComposer());
                    // The eject ramp's entry (docs/page-builder.md D2): a route that
                    // declares response.html.view offers "Eject to template". The
                    // saved source is authoritative — a draft's view: line does not
                    // eject until applied.
                    String saved = chrome.saved();
                    model.put("ejectableView", path.startsWith("web/")
                            && path.endsWith(".yml") && saved != null
                            && saved.matches("(?s).*(?m)^\\s*view:\\s*\\S.*"));
                    // On a route SQL file, offer the 2-way SQL builder inline (insert into the
                    // editor): populate its table dropdown from the schema overlay. The list
                    // reads through the shared memo — an editor page render must not re-parse
                    // schema.json every time.
                    if (Boolean.TRUE.equals(model.get("isRouteSql"))) {
                        java.util.List<String> tables = docCache.tableNames();
                        model.put("tables", tables);
                        model.put("hasTables", !tables.isEmpty());
                    }
                    return model;
                })
        // The mail composer (docs/html-email.md D4). The channel list reads
        // the manifest's mail channels raw — display never resolves ${ENV}
        // placeholders — and the composer page opens a template as blocks
        // only when it matches the composer grammar (MailComposer.parse);
        // anything else keeps the source editor as its authoring surface.
        ;
    }

    /** Mail and page composers. */
    private void mailAndPageComposers(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.mail", params -> {
                    java.util.List<Map<String, Object>> channels = new java.util.ArrayList<>();
                    for (String name : notificationChannels.names()) {
                        io.tesseraql.yaml.notify.NotificationChannels.Channel channel = notificationChannels
                                .require(name);
                        if (!io.tesseraql.yaml.notify.NotificationChannels.MAIL
                                .equals(channel.type())) {
                            continue;
                        }
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("name", name);
                        row.put("from", channel.raw("from").orElse(""));
                        row.put("to", channel.raw("to").orElse(""));
                        row.put("subject", channel.raw("subject").orElse(""));
                        String template = channel.raw("template").orElse("");
                        row.put("template", template);
                        boolean html = template.endsWith(".html");
                        row.put("isHtml", html);
                        String text = template.isEmpty()
                                ? null
                                : draftOrSource(studio, template);
                        row.put("exists", text != null);
                        row.put("composable", html && (text == null
                                || io.tesseraql.studio.MailComposer.parse(text)
                                        .isPresent()));
                        channels.add(row);
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("channels", channels);
                    model.put("hasChannels", !channels.isEmpty());
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                .register("studio.mailComposer", params -> {
                    String path = String.valueOf(params.get("path"));
                    EditorChrome chrome = EditorChrome.of(studio, studioEdit, path, params);
                    String text = chrome.text();
                    Map<String, Object> model = chrome.model();
                    model.put("isNew", text == null);
                    // The preview model mirrors MailNotifier's: payload + event.
                    String sample = chrome.sampleModel();
                    if (sample == null || sample.isBlank()) {
                        model.put("sampleModel",
                                "payload:\n  name: Example\nevent:\n  app: app\n  id: evt-1\n");
                    }
                    // A missing template opens on the starter document; anything else opens
                    // as blocks only when it matches the composer grammar.
                    java.util.Optional<io.tesseraql.studio.MailComposer.Composition> parsed = text == null
                            ? java.util.Optional
                                    .of(io.tesseraql.studio.MailComposer.starter())
                            : Eligibility.of(path, text).composition();
                    model.put("composable", parsed.isPresent());
                    parsed.ifPresent(composition -> {
                        model.put("title", composition.title());
                        model.put("preheader", composition.preheader());
                        model.put("content",
                                io.tesseraql.studio.MailComposer.write(composition));
                        model.put("blocks",
                                io.tesseraql.studio.MailComposer.blockRows(composition));
                    });
                    model.put("palette", io.tesseraql.studio.MailComposer.paletteRows());
                    return model;
                })
                // The visual page builder (docs/page-builder.md D1): the split's prefix
                // and suffix are verbatim captures — the client's canvas edits only the
                // region, and export is plain concatenation, so the wrapper (and any
                // scaffold-checksum header) survives byte-for-byte.
                .register("studio.pageBuilder", params -> {
                    String path = String.valueOf(params.get("path"));
                    EditorChrome chrome = EditorChrome.of(studio, studioEdit, path, params);
                    Map<String, Object> model = chrome.model();
                    Eligibility eligible = Eligibility.of(path, chrome.text());
                    boolean composable = eligible.builder();
                    model.put("composable", composable);
                    if (composable) {
                        io.tesseraql.studio.PageBuilder.Parts split = eligible.parts()
                                .orElseThrow();
                        model.put("prefix", split.prefix());
                        model.put("region", split.region());
                        model.put("suffix", split.suffix());
                        model.put("regionClass", split.regionClass());
                        model.put("shellWrapped", split.shellWrapped());
                    }
                    return model;
                })
                // The mail test send (docs/pages-and-mail-lints.md follow-ups): the
                // composer's draft body renders (content-aware) and delivers over
                // the channel's own transport to an explicit recipient. Gated like
                // the other outward-reaching dev tools (edit roles + the test
                // runner opt-in); failures return as a message, never a 500.
                .register("studio.mailTestSend", params -> {
                    studioEdit.requireEdit(params);
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    if (!studioTests.isEnabled()) {
                        model.put("ok", false);
                        model.put("message", "Test send is disabled — it needs the"
                                + " sandboxed dev tools opt-in"
                                + " (tesseraql.studio.testRunner.enabled)");
                        return model;
                    }
                    String path = String.valueOf(params.get("path"));
                    String to = String.valueOf(params.get("to"));
                    Object content = params.get("content");
                    Object sample = params.get("sampleModel");
                    String channelName = null;
                    for (String name : notificationChannels.names()) {
                        io.tesseraql.yaml.notify.NotificationChannels.Channel candidate = notificationChannels
                                .require(name);
                        if (io.tesseraql.yaml.notify.NotificationChannels.MAIL
                                .equals(candidate.type())
                                && path.equals(candidate.raw("template")
                                        .orElse(null))) {
                            channelName = name;
                            break;
                        }
                    }
                    if (channelName == null) {
                        model.put("ok", false);
                        model.put("message", "No mail channel declares template '"
                                + path + "' — wire one under"
                                + " tesseraql.notifications.channels first");
                        return model;
                    }
                    io.tesseraql.studio.StudioService.RenderResult render = studio.render(
                            path,
                            content == null ? null : String.valueOf(content),
                            sample == null ? null : String.valueOf(sample));
                    if (!render.ok()) {
                        model.put("ok", false);
                        model.put("message", "Render failed: " + render.error());
                        return model;
                    }
                    try {
                        new io.tesseraql.yaml.notify.MailNotifier(appHome).sendTest(
                                notificationChannels.require(channelName),
                                studio.sampleModelMap(path, sample == null
                                        ? null
                                        : String.valueOf(sample)),
                                render.output(), path.endsWith(".html"), to);
                        model.put("ok", true);
                        model.put("message", "Sent to " + to + " via channel '"
                                + channelName + "'");
                    } catch (RuntimeException ex) {
                        model.put("ok", false);
                        model.put("message", ex.getMessage());
                    }
                    return model;
                })
        // The Pages overview (docs/pages-and-mail-lints.md D1): every route
        // with an HTML response and its rendering mode, from a fresh
        // manifest load (the eject precedent — the boot snapshot may be
        // stale). Read-only: actions link into the existing surfaces.
        ;
    }

    /** Pages, views, drafts and the copilot. */
    private void pagesViewsDraftsCopilot(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.pages", params -> {
                    io.tesseraql.yaml.manifest.AppManifest fresh = new ManifestLoader()
                            .load(appHome);
                    io.tesseraql.core.files.ConfinedPath confined = io.tesseraql.core.files.ConfinedPath
                            .under(appHome);
                    java.nio.file.Path home = confined.root();
                    java.util.List<Map<String, Object>> pages = new java.util.ArrayList<>();
                    for (io.tesseraql.yaml.manifest.RouteFile route : fresh.routes()) {
                        var response = route.definition().response();
                        var html = response == null ? null : response.html();
                        if (html == null
                                || (html.view() == null && html.template() == null)) {
                            continue;
                        }
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", route.definition().id());
                        row.put("method", route.httpMethod());
                        row.put("path", route.urlPath());
                        row.put("source", home.relativize(route.source()).toString()
                                .replace('\\', '/'));
                        java.nio.file.Path routeDir = route.source().getParent();
                        boolean isView = html.view() != null;
                        String ref = isView ? html.view() : html.template();
                        row.put("mode", isView ? "view" : "template");
                        row.put("ref", ref);
                        // A view ref is the document's id in the manifest registry
                        // (docs/view-composition.md wave 1); a template ref stays a
                        // path resolved colocated-first.
                        java.nio.file.Path file;
                        if (isView) {
                            var registered = fresh.viewById(ref);
                            file = registered == null ? null : registered.source();
                        } else {
                            file = routeDir.resolve(ref).normalize();
                            if (!java.nio.file.Files.isRegularFile(file)) {
                                file = home.resolve("templates").resolve(ref)
                                        .normalize();
                            }
                        }
                        String refPath = file != null
                                && java.nio.file.Files.isRegularFile(file)
                                && confined.contains(file)
                                        ? home.relativize(file).toString()
                                                .replace('\\', '/')
                                        : null;
                        row.put("refPath", refPath);
                        String kind = "";
                        boolean ejectable = false;
                        boolean builderEligible = false;
                        if (isView && refPath != null) {
                            kind = fresh.viewById(ref).spec().view();
                            // Every view kind ejects (docs/pages-and-mail-lints.md
                            // follow-ups added the dashboard).
                            ejectable = io.tesseraql.yaml.view.ViewSpec.LIST.equals(kind)
                                    || io.tesseraql.yaml.view.ViewSpec.DETAIL
                                            .equals(kind)
                                    || io.tesseraql.yaml.view.ViewSpec.DASHBOARD
                                            .equals(kind)
                                    || io.tesseraql.yaml.view.ViewSpec.FORM.equals(kind);
                        } else if (!isView && refPath != null
                                && refPath.endsWith(".html")) {
                            builderEligible = Eligibility
                                    .of(refPath, studio.sourceIfExists(refPath)).builder();
                        }
                        row.put("kind", kind);
                        row.put("ejectable", ejectable);
                        row.put("builderEligible", builderEligible);
                        pages.add(row);
                    }
                    pages.sort(java.util.Comparator
                            .comparing(page -> String.valueOf(page.get("path"))));
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("pages", pages);
                    model.put("hasPages", !pages.isEmpty());
                    return model;
                })
                // The eject ramp (docs/page-builder.md D2): the CLI's eject-view
                // orchestration, edit-gated. The manifest re-loads from disk so the
                // route state is the on-disk truth, not the boot snapshot; a
                // successful flip reloads routes (the scaffold-apply precedent).
                .register("studio.ejectView", params -> {
                    studioEdit.requireEdit(params);
                    boolean confirm = "true"
                            .equals(String.valueOf(params.get("confirm")));
                    boolean force = "true".equals(String.valueOf(params.get("force")));
                    studioEdit.requireConfirm(confirm || force);
                    String path = String.valueOf(params.get("path"));
                    io.tesseraql.yaml.view.ViewEjects.Result result = io.tesseraql.yaml.view.ViewEjects
                            .eject(appHome,
                                    new ManifestLoader().load(appHome), path, force);
                    if (!result.blocked()) {
                        reloader.reload();
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("routePath", result.routePath());
                    model.put("templatePath", result.templatePath());
                    model.put("blocked", result.blocked());
                    return model;
                })
                .register("studio.save", params -> {
                    studioEdit.requireEdit(params);
                    String path = String.valueOf(params.get("path"));
                    Object content = params.get("content");
                    studio.saveDraft(path, content == null ? "" : String.valueOf(content));
                    return Map.of("saved", path);
                })
                // The form-driven route editor (roadmap Phase 43, Track J1): the
                // governed fields — recipe, auth, policy, CSRF, inputs — as structured
                // form fields over the same document tree; saving lands a draft and the
                // text editor stays the escape hatch.
                // The Studio copilot (roadmap Phase 44, decision point 8): a chat
                // loop against an OPERATOR-CONFIGURED model endpoint — no model
                // shipped, no key in app source (the credential resolves lazily
                // through the config placeholder chain), reads free, writes only as
                // audited DRAFTS a human applies in the editor.
                .register("studio.copilot.view", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("enabled", copilotService != null);
                    boolean canEdit = studioEdit.canEdit(params);
                    model.put("editable", canEdit);
                    // Entries arrive pre-rendered (CopilotFragments, the single
                    // markup source shared with the SSE done event); the message
                    // input too, so the htmx out-of-band clear cannot drift from
                    // the page's own form.
                    model.put("entries", copilotService == null
                            ? java.util.List.of()
                            : copilotService.transcript(actorOf(params)).stream()
                                    .map(io.tesseraql.studio.CopilotFragments::entryHtml)
                                    .toList());
                    model.put("input",
                            io.tesseraql.studio.CopilotFragments.messageInput(false));
                    return model;
                })
                .register("studio.copilot.reset", params -> {
                    requireCopilot(copilotService);
                    copilotService.reset(actorOf(params));
                    return Map.of("reset", true);
                });
    }

    /** Routes and the menu. */
    private void routesAndMenu(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.routeForm.view", params -> {
                    String path = String.valueOf(params.get("path"));
                    boolean canEdit = studioEdit.canEdit(params);
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("form", studio.routeForm(path));
                    putEditFlags(model, canEdit);
                    // Framework-derived options (roadmap Phase 57): the same surfaces
                    // the shipped JSON Schema is drift-tested against, so the schema,
                    // the linter, and this form can never disagree.
                    model.put("recipes", io.tesseraql.yaml.lint.AppLinter
                            .knownRouteRecipes().stream().sorted().toList());
                    model.put("authOptions", io.tesseraql.yaml.lint.AppLinter
                            .knownAuthModes().stream().sorted().toList());
                    model.put("inputTypes", io.tesseraql.yaml.lint.AppLinter
                            .knownInputTypes().stream().sorted().toList());
                    java.util.List<String> policyIds = studio.securityPolicies().stream()
                            .map(policy -> String.valueOf(policy.get("id"))).toList();
                    model.put("policyOptions", policyIds);
                    // Field domains (docs/field-domains.md): reference one instead of
                    // restating the field's constraints per route.
                    model.put("domainOptions", studio.domainNames());
                    model.put("slots", ROUTE_FORM_INPUT_SLOTS);
                    return model;
                })
                .register("studio.routeForm.save", params -> {
                    studioEdit.requireEdit(params);
                    String path = String.valueOf(params.get("path"));
                    java.util.List<io.tesseraql.studio.StudioService.FormInput> inputs = new java.util.ArrayList<>();
                    for (int i = 0; i < ROUTE_FORM_INPUT_SLOTS; i++) {
                        inputs.add(new io.tesseraql.studio.StudioService.FormInput(
                                str(params, "in" + i + "name"),
                                str(params, "in" + i + "type"),
                                params.get("in" + i + "req") != null,
                                str(params, "in" + i + "min"),
                                str(params, "in" + i + "max"),
                                str(params, "in" + i + "maxlen"),
                                str(params, "in" + i + "minlen"),
                                str(params, "in" + i + "pattern"),
                                str(params, "in" + i + "enum"),
                                str(params, "in" + i + "domain")));
                    }
                    studio.routeFormSave(path, str(params, "recipe"),
                            str(params, "auth"), str(params, "policy"),
                            str(params, "csrf"), inputs);
                    return Map.of("saved", path);
                })
                .register("studio.newRoute", params -> {
                    studioEdit.requireEdit(params);
                    String path = String.valueOf(params.get("path"));
                    Object recipe = params.get("recipe");
                    studio.newRouteDraft(path,
                            recipe == null ? "query-json" : String.valueOf(recipe));
                    return Map.of("created", path);
                })
                // Menu editor (app sidebar menu, Slice 2): read/write the app's declarative,
                // role-filtered config/menu.yml. view renders the current items + add form;
                // add/remove/move mutate the file (edit-gated + audited in StudioService).
                // preview renders the menu exactly as a caller with a chosen role/permission
                // would see it (server-side visibleFor). A menu edit is deliberately not tied
                // to a full route reload (like createMigration): renderers read the menu via
                // MenuSpec.live (a stat-cheap re-read), so an edit shows on the next rendered
                // page immediately, yet editing the sidebar can never be broken by an unrelated
                // route that fails to recompile.
                .register("studio.menu.view", params -> {
                    boolean canEdit = studioEdit.canEdit(params);
                    java.util.List<io.tesseraql.yaml.menu.MenuSpec.MenuItem> items = studio
                            .menuItems();
                    // Known route paths back both href autocomplete and the per-item
                    // dangling-href hint (an href that matches no served route).
                    java.util.List<String> paths = studio.routePaths();
                    java.util.Set<String> pathSet = new java.util.HashSet<>(paths);
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (int i = 0; i < items.size(); i++) {
                        io.tesseraql.yaml.menu.MenuSpec.MenuItem item = items.get(i);
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("index", i);
                        row.put("first", i == 0);
                        row.put("last", i == items.size() - 1);
                        row.put("label", item.label());
                        row.put("href", item.href());
                        row.put("icon", item.icon());
                        boolean isPublic = item.roles().isEmpty()
                                && item.permissions().isEmpty();
                        row.put("public", isPublic);
                        row.put("visibility", menuVisibility(item));
                        // An href pointing at no served route is flagged (not an error — it
                        // may be an external link, an asset, or another mounted app).
                        row.put("unmatched", item.href() != null && !item.href().isBlank()
                                && !pathSet.contains(item.href()));
                        rows.add(row);
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    putEditFlags(model, canEdit);
                    model.put("items", rows);
                    model.put("hasItems", !rows.isEmpty());
                    model.put("roleOptions", studio.knownRoles());
                    model.put("permissionOptions", studio.knownPermissions());
                    model.put("hrefOptions", paths);
                    model.put("iconOptions", MENU_ICON_OPTIONS);
                    return model;
                })
                .register("studio.menu.add", params -> {
                    studioEdit.requireEdit(params);
                    // The item's own visibility field rides "itemPermissions": the workshop
                    // stamps both gate spellings with the caller's identity, so a data field
                    // named "permissions" could never survive the hop.
                    studio.addMenuItem(str(params, "label"), str(params, "href"),
                            str(params, "icon"), str(params, "roles"),
                            str(params, "itemPermissions"), actorOf(params));
                    return Map.of("added", true);
                })
                .register("studio.menu.remove", params -> {
                    studioEdit.requireEdit(params);
                    studio.removeMenuItem(menuIndex(params.get("index")), actorOf(params));
                    return Map.of("removed", true);
                })
                .register("studio.menu.move", params -> {
                    studioEdit.requireEdit(params);
                    int delta = "up".equals(String.valueOf(params.get("dir"))) ? -1 : 1;
                    studio.moveMenuItem(menuIndex(params.get("index")), delta,
                            actorOf(params));
                    return Map.of("moved", true);
                })
                .register("studio.menu.preview", params -> {
                    String role = str(params, "role");
                    String permission = str(params, "permission");
                    java.util.List<String> roles = role == null
                            ? java.util.List.of()
                            : java.util.List.of(role);
                    java.util.List<String> perms = permission == null
                            ? java.util.List.of()
                            : java.util.List.of(permission);
                    java.util.List<Map<String, Object>> visible = new java.util.ArrayList<>();
                    for (io.tesseraql.yaml.menu.MenuSpec.MenuItem item : io.tesseraql.yaml.menu.MenuSpec
                            .load(manifest.appHome()).visibleFor(roles, perms)) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("label", item.label());
                        row.put("href", item.href());
                        row.put("icon", item.icon());
                        visible.add(row);
                    }
                    String who = role == null && permission == null
                            ? "an anonymous caller"
                            : java.util.stream.Stream.of(
                                    role == null ? null : "role " + role,
                                    permission == null ? null : "permission " + permission)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(java.util.stream.Collectors.joining(" + "));
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("items", visible);
                    model.put("empty", visible.isEmpty());
                    model.put("summary", "Showing the menu for " + who + ".");
                    return model;
                })
        // Health dashboard (governance): runs the same AppLinter as the CLI/Maven lint
        // over the app and surfaces its findings grouped by severity, each linking to
        // the source editor. An app that fails to even load is shown as one blocking
        // finding rather than a 500, so the dashboard is usable exactly when it matters.
        ;
    }

    /** Health, security, policy and the try-it console. */
    private void healthSecurityPolicyTry(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.health", params -> {
                    java.util.List<io.tesseraql.yaml.lint.LintFinding> findings;
                    try {
                        findings = studio.health();
                    } catch (RuntimeException ex) {
                        findings = java.util.List.of(new io.tesseraql.yaml.lint.LintFinding(
                                "TQL-STUDIO-4225",
                                io.tesseraql.yaml.lint.LintFinding.Severity.ERROR, "app",
                                "The app failed to load: " + ex.getMessage()));
                    }
                    int errors = 0;
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    for (io.tesseraql.yaml.lint.LintFinding finding : findings) {
                        if (finding.isError()) {
                            errors++;
                        }
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("code", finding.code());
                        row.put("severity", finding.severity());
                        row.put("error", finding.isError());
                        row.put("source", finding.source());
                        row.put("line", finding.line());
                        row.put("message", finding.message());
                        // Link to the source editor when the finding names an app file (not a
                        // config-level pseudo-source like "config"/"app").
                        boolean editable = finding.source() != null
                                && finding.source().contains("/");
                        row.put("sourceUrl", editable
                                ? sourceEditorUrl(finding.source())
                                : null);
                        rows.add(row);
                    }
                    // Severity chips + search (studio-ux-refresh slice 6): the table
                    // narrows, the stat tiles keep the full counts.
                    String severity = str(params, "severity");
                    String healthQ = str(params, "q");
                    java.util.List<Map<String, Object>> shown = rows.stream()
                            .filter(row -> severity == null
                                    || severity.equals(row.get("severity")))
                            .filter(row -> healthQ == null || (row.get("code") + " "
                                    + row.get("source") + " " + row.get("message"))
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains(healthQ
                                            .toLowerCase(java.util.Locale.ROOT)))
                            .toList();
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("findings", shown);
                    model.put("shown", shown.size());
                    model.put("severity", severity == null ? "" : severity);
                    model.put("query", healthQ == null ? "" : healthQ);
                    model.put("total", findings.size());
                    model.put("errors", errors);
                    model.put("warnings", findings.size() - errors);
                    model.put("clean", findings.isEmpty());
                    return model;
                })
                // Security overview (governance): the route-to-policy map + the app's policies,
                // flagging unprotected routes (no auth), CSRF gaps (state-changing browser
                // routes without CSRF), and routes referencing an undefined policy. Read-only
                // in this slice; editing policies is a later slice.
                .register("studio.security", params -> {
                    java.util.List<Map<String, Object>> policies = studio
                            .securityPolicies();
                    java.util.Set<String> policyIds = new java.util.HashSet<>();
                    for (Map<String, Object> policy : policies) {
                        policyIds.add(String.valueOf(policy.get("id")));
                    }
                    java.util.List<Map<String, Object>> routes = studio.routeSecurity();
                    int unprotected = 0;
                    int csrfGaps = 0;
                    int unknownPolicies = 0;
                    for (Map<String, Object> route : routes) {
                        if (Boolean.TRUE.equals(route.get("unprotected"))) {
                            unprotected++;
                        }
                        if (Boolean.TRUE.equals(route.get("csrfGap"))) {
                            csrfGaps++;
                        }
                        Object policy = route.get("policy");
                        boolean unknown = policy != null
                                && !policyIds.contains(String.valueOf(policy));
                        route.put("unknownPolicy", unknown);
                        if (unknown) {
                            unknownPolicies++;
                        }
                        route.put("sourceUrl",
                                sourceEditorUrl(String.valueOf(route.get("source"))));
                    }
                    // Reverse index: which routes each policy guards, so a policy links to
                    // its consumers and a policy used by none is flagged as dead config.
                    Map<String, java.util.List<Map<String, Object>>> byPolicy = new java.util.HashMap<>();
                    for (Map<String, Object> route : routes) {
                        Object policy = route.get("policy");
                        if (policy != null) {
                            byPolicy.computeIfAbsent(String.valueOf(policy),
                                    key -> new java.util.ArrayList<>()).add(route);
                        }
                    }
                    int unusedPolicies = 0;
                    for (Map<String, Object> policy : policies) {
                        java.util.List<Map<String, Object>> used = byPolicy.getOrDefault(
                                String.valueOf(policy.get("id")), java.util.List.of());
                        policy.put("routes", used);
                        policy.put("usedBy", used.size());
                        policy.put("unused", used.isEmpty());
                        if (used.isEmpty()) {
                            unusedPolicies++;
                        }
                    }
                    // Status chips + search (studio-ux-refresh slice 6): the routes
                    // table narrows, the stat tiles keep the full counts.
                    String status = str(params, "status");
                    String secQ = str(params, "q");
                    java.util.List<Map<String, Object>> shownRoutes = routes.stream()
                            .filter(route -> switch (status == null ? "" : status) {
                                case "unprotected" -> Boolean.TRUE
                                        .equals(route.get("unprotected"));
                                case "csrf-gap" -> Boolean.TRUE
                                        .equals(route.get("csrfGap"));
                                case "undefined-policy" -> Boolean.TRUE
                                        .equals(route.get("unknownPolicy"));
                                default -> true;
                            })
                            .filter(route -> secQ == null || (route.get("method") + " "
                                    + route.get("path") + " " + route.get("policy"))
                                    .toLowerCase(java.util.Locale.ROOT)
                                    .contains(secQ.toLowerCase(java.util.Locale.ROOT)))
                            .toList();
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("routes", shownRoutes);
                    model.put("shown", shownRoutes.size());
                    model.put("status", status == null ? "" : status);
                    model.put("query", secQ == null ? "" : secQ);
                    model.put("policies", policies);
                    model.put("totalRoutes", routes.size());
                    model.put("unprotected", unprotected);
                    model.put("csrfGaps", csrfGaps);
                    model.put("unknownPolicies", unknownPolicies);
                    model.put("unusedPolicies", unusedPolicies);
                    model.put("policyCount", policies.size());
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                // Policy editing (security overview, edit slice): grant/revoke a role or
                // permission rule on a policy. StudioService writes the policy's full rule set
                // to config/overlay.yml (the base config is untouched; overlay overrides it),
                // gated + audited; then the PolicyEngine is rebuilt from the fresh config and
                // rebound, so the change is authorized live on the next request — no restart.
                .register("studio.policyAddRule", params -> {
                    studioEdit.requireEdit(params);
                    studio.addPolicyRule(str(params, "policy"), str(params, "kind"),
                            str(params, "value"), actorOf(params));
                    rebindPolicyEngine(context, manifest.appHome());
                    return Map.of("added", true);
                })
                .register("studio.policyRemoveRule", params -> {
                    studioEdit.requireEdit(params);
                    studio.removePolicyRule(str(params, "policy"), str(params, "kind"),
                            str(params, "value"), actorOf(params));
                    rebindPolicyEngine(context, manifest.appHome());
                    return Map.of("removed", true);
                })
                // API try-it console: invoke one of the app's own routes and show the raw
                // response (status, headers, body). The run proxies a loopback HTTP call to
                // 127.0.0.1:<port><path> — confined to app-relative paths (no host, no scheme),
                // so it can never reach off-box (no SSRF). The target route enforces its own
                // security, so try-it grants nothing extra: public routes just work, bearer
                // routes take a token the caller pastes. (Browser-session forwarding is a later
                // slice.) view supplies the served route paths for the path autocomplete.
                .register("studio.tryit", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("pathOptions", studio.routePaths());
                    // Deep-linked ?path=&method= (e.g. from a route's docs page) prefills the
                    // form + a request-body/query skeleton from the route's declared inputs.
                    model.put("prefill", studio.tryPrefill(str(params, "method"),
                            str(params, "path")));
                    return model;
                })
                .register("studio.tryRun", params -> {
                    Map<String, Object> result = tryInvoke(port.getAsInt(), params);
                    // The test recorder (Track J3): a recordable invocation offers "Save
                    // as test case" on the result fragment, echoing what was sent.
                    result.putAll(studio.recordability(
                            String.valueOf(result.get("method")), str(params, "path")));
                    result.put("query", str(params, "query"));
                    result.put("sentBody", str(params, "body"));
                    return result;
                })
                .register("studio.tryRecord", params -> {
                    studioEdit.requireEdit(params);
                    String method = String.valueOf(params.get("method"));
                    String path = String.valueOf(params.get("path"));
                    Map<String, Object> recordable = studio.recordability(method, path);
                    if (!Boolean.TRUE.equals(recordable.get("recordable"))) {
                        throw new io.tesseraql.core.error.TqlException(
                                new io.tesseraql.core.error.TqlErrorCode(
                                        io.tesseraql.core.error.TqlDomain.STUDIO, 4233),
                                String.valueOf(recordable.get("reason")));
                    }
                    Map<String, String> query = parseQueryString(str(params, "query"));
                    Map<String, Object> body = parseJsonObject(str(params, "body"));
                    Map<String, Object> caseParams = studio.recordedCaseParams(method,
                            path, query, body);
                    // The sandbox captures the expectation so the recorded case is a
                    // real data regression, passing by construction. Optional: without
                    // the test runner the case still records (no expectation).
                    Integer rowCount = null;
                    if (studioTests.isEnabled()) {
                        io.tesseraql.yaml.manifest.RouteFile match = null;
                        for (io.tesseraql.yaml.manifest.RouteFile route : manifest
                                .routes()) {
                            if (path.equals(route.urlPath())
                                    && method.equalsIgnoreCase(route.httpMethod())) {
                                match = route;
                                break;
                            }
                        }
                        if (match != null) {
                            Map<String, Object> recordContext = new java.util.LinkedHashMap<>();
                            recordContext.put("query", query);
                            recordContext.put("params", body.isEmpty() ? query : body);
                            rowCount = studioTests.sandboxRowCount(match.definition(),
                                    match.source().getParent(), recordContext);
                        }
                    }
                    String name = studio.appendRecordedTest(str(params, "name"),
                            studio.recordedSqlFile(method, path), caseParams, rowCount,
                            actorOf(params));
                    return Map.of("recorded", name);
                })
        // Config viewer (governance): the effective merged configuration (application
        // .yml + tesseraql.yml + overlay.yml), flattened to dotted keys, with secret
        // values redacted. Read-only — a curated overlay-backed editor is a later slice.
        // Connector & SSO authoring (roadmap Phase 43, Track J2): the managed
        // connector config — egress allow-lists, outbound/poll credentials,
        // webhook verifiers — and the IAM wizards write config/overlay.yml
        // through the same gated path as policies. Secret REFERENCES only;
        // egress changes are always confirm-gated; all of it restart-bound
        // (these sections load at boot), which the pages state.
        ;
    }

    /** Connectors and the identity wizards. */
    private void connectorsAndIdentityWizards(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.connectors.view", params -> {
                    boolean canEdit = studioEdit.canEdit(params);
                    Map<String, Object> model = new java.util.LinkedHashMap<>(
                            studio.connectorsView());
                    model.put("saved", params.get("saved"));
                    putEditFlags(model, canEdit);
                    return model;
                })
                .register("studio.connectors.egress", params -> {
                    studioEdit.requireEdit(params);
                    requireExplicitConfirm(params, "Egress allow-list changes");
                    studio.updateEgressHosts(str(params, "scope"), str(params, "host"),
                            "true".equals(String.valueOf(params.get("remove"))),
                            actorOf(params));
                    return Map.of("saved", true);
                })
                .register("studio.connectors.webhook", params -> {
                    studioEdit.requireEdit(params);
                    studio.writeWebhookVerifier(str(params, "name"),
                            str(params, "secret"), str(params, "signatureHeader"),
                            str(params, "timestampHeader"), str(params, "idHeader"),
                            str(params, "tolerance"), actorOf(params));
                    return Map.of("saved", true);
                })
                .register("studio.connectors.credential", params -> {
                    studioEdit.requireEdit(params);
                    studio.writeConnectorCredential(str(params, "scope"),
                            str(params, "name"), str(params, "type"),
                            str(params, "token"), str(params, "username"),
                            str(params, "password"), str(params, "header"),
                            str(params, "value"), actorOf(params));
                    return Map.of("saved", true);
                })
                // The wizards' Review-YAML step (studio-ux-refresh slice 5): render the
                // same .yml.tpl the download serves, from the mounted studio app's
                // extracted tree, so the preview IS the artifact. No edit gate — it
                // echoes the caller's own input and writes nothing.
                .register("studio.wizard.preview", params -> {
                    java.nio.file.Path studioAppRoot = io.tesseraql.yaml.config.WorkHome
                            .resolve(appHome, manifest.config()).resolve("apps")
                            .resolve("studio");
                    Map<String, Object> tplParams = new java.util.LinkedHashMap<>(params);
                    String kind = str(tplParams, "kind");
                    tplParams.remove("kind");
                    return Map.of("yaml", io.tesseraql.studio.StudioService
                            .renderWizardYaml(studioAppRoot, kind, tplParams));
                })
                .register("studio.wizard.oidc.apply", params -> {
                    studioEdit.requireEdit(params);
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("tesseraql.oidc.enabled", true);
                    values.put("tesseraql.oidc.discoveryUri",
                            requiredParam(params, "discoveryUri"));
                    values.put("tesseraql.oidc.clientId",
                            requiredParam(params, "clientId"));
                    values.put("tesseraql.oidc.redirectUri",
                            requiredParam(params, "redirectUri"));
                    putIfPresent(values, "tesseraql.oidc.scopes", params, "scopes");
                    putIfPresent(values, "tesseraql.oidc.postLoginUrl", params,
                            "postLoginUrl");
                    String clientSecret = io.tesseraql.studio.StudioService
                            .secretReferenceOrNull("The OIDC client secret",
                                    str(params, "clientSecret"));
                    if (clientSecret != null) {
                        values.put("tesseraql.oidc.clientSecret", clientSecret);
                    }
                    values.put("tesseraql.oidc.link.enabled", true);
                    values.put("tesseraql.oidc.link.provision",
                            "true".equals(str(params, "provision")));
                    studio.writeOverlaySection(values, "sso", actorOf(params));
                    return Map.of("applied", "oidc");
                })
                .register("studio.wizard.saml.apply", params -> {
                    studioEdit.requireEdit(params);
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("tesseraql.saml.enabled", true);
                    values.put("tesseraql.saml.sp.audience",
                            requiredParam(params, "spAudience"));
                    values.put("tesseraql.saml.sp.acsUrl",
                            requiredParam(params, "acsUrl"));
                    putIfPresent(values, "tesseraql.saml.sp.nameIdFormat", params,
                            "nameIdFormat");
                    values.put("tesseraql.saml.idp.ssoUrl",
                            requiredParam(params, "ssoUrl"));
                    putIfPresent(values, "tesseraql.saml.idp.sloUrl", params, "sloUrl");
                    putIfPresent(values, "tesseraql.saml.idp.metadata", params,
                            "idpMetadataPath");
                    putIfPresent(values, "tesseraql.saml.idp.publicKey", params,
                            "publicKeyPath");
                    values.put("tesseraql.saml.attributes.loginId",
                            requiredParam(params, "loginIdAttribute"));
                    putIfPresent(values, "tesseraql.saml.attributes.email", params,
                            "emailAttribute");
                    values.put("tesseraql.saml.link.enabled", true);
                    values.put("tesseraql.saml.link.provision",
                            "true".equals(str(params, "provision")));
                    studio.writeOverlaySection(values, "sso", actorOf(params));
                    return Map.of("applied", "saml");
                })
                .register("studio.wizard.scim.apply", params -> {
                    studioEdit.requireEdit(params);
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("tesseraql.scim.enabled", true);
                    values.put("tesseraql.scim.groups.enabled",
                            "true".equals(str(params, "groups")));
                    boolean outbound = "true".equals(str(params, "outbound"));
                    values.put("tesseraql.scim.outbound.enabled", outbound);
                    String outboundUrl = str(params, "outboundUrl");
                    if (outbound && outboundUrl != null && !outboundUrl.isBlank()) {
                        values.put("tesseraql.scim.outbound.target.url",
                                outboundUrl.trim());
                        String token = io.tesseraql.studio.StudioService
                                .secretReferenceOrNull("The SCIM outbound token",
                                        str(params, "tokenRef"));
                        if (token == null) {
                            throw new io.tesseraql.core.error.TqlException(
                                    new io.tesseraql.core.error.TqlErrorCode(
                                            io.tesseraql.core.error.TqlDomain.STUDIO,
                                            4231),
                                    "An outbound SCIM target needs a token secret "
                                            + "reference like ${secret.env.SCIM_TOKEN}");
                        }
                        values.put("tesseraql.scim.outbound.target.token", token);
                    }
                    studio.writeOverlaySection(values, "sso", actorOf(params));
                    return Map.of("applied", "scim");
                })
                .register("studio.wizard.identity.apply", params -> {
                    studioEdit.requireEdit(params);
                    String realmId = requiredParam(params, "realmId");
                    String prefix = "tesseraql.identity.realms." + realmId + ".";
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("tesseraql.identity.defaultRealm", realmId);
                    values.put(prefix + "type", requiredParam(params, "type"));
                    values.put(prefix + "datasource",
                            requiredParam(params, "datasource"));
                    putIfPresent(values, prefix + "sqlRoot", params, "sqlRoot");
                    putIfPresent(values, prefix + "capabilities.userManagement", params,
                            "userManagement");
                    studio.writeOverlaySection(values, "sso", actorOf(params));
                    return Map.of("applied", "identity");
                });
    }

    /** The config viewer/editor and feature flags. */
    private void configAndFlags(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.config", params -> {
                    java.util.List<Map<String, Object>> rows = studio.effectiveConfig();
                    long secrets = rows.stream()
                            .filter(r -> Boolean.TRUE.equals(r.get("secret"))).count();
                    // Key/value search (studio-ux-refresh slice 6): the table
                    // narrows, the header keeps the full counts.
                    String cfgQ = str(params, "q");
                    java.util.List<Map<String, Object>> shown = cfgQ == null
                            ? rows
                            : rows.stream()
                                    .filter(row -> (row.get("key") + " "
                                            + (Boolean.TRUE.equals(row.get("secret"))
                                                    ? ""
                                                    : row.get("value")))
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .contains(cfgQ.toLowerCase(
                                                    java.util.Locale.ROOT)))
                                    .toList();
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("rows", shown);
                    model.put("shown", shown.size());
                    model.put("query", cfgQ == null ? "" : cfgQ);
                    model.put("count", rows.size());
                    model.put("secretCount", secrets);
                    model.put("settings", studio.editableSettings());
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                // Config editor (curated): override one whitelisted, restart-to-apply setting
                // in config/overlay.yml (base untouched), or remove it when blank. Edit-gated +
                // audited; only StudioService's whitelist of safe scalar keys is accepted.
                .register("studio.configSet", params -> {
                    studioEdit.requireEdit(params);
                    Object value = params.get("value");
                    studio.setConfigValue(str(params, "key"),
                            value == null ? "" : String.valueOf(value), actorOf(params));
                    return Map.of("saved", true);
                })
                // Live feature flags editor: set/remove a flag in config/flags.yml. The request
                // binder reads flags.<name> live, so a change takes effect on the next request
                // (no restart). Edit-gated + audited.
                .register("studio.flags", params -> {
                    Map<String, Object> flags = studio.flags();
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    flags.forEach((name, value) -> {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("name", name);
                        row.put("value", value == null ? "" : String.valueOf(value));
                        row.put("type", value instanceof Boolean
                                ? "boolean"
                                : value instanceof Number ? "number" : "string");
                        // The on/off state of a boolean flag drives the one-click toggle.
                        row.put("on", value instanceof Boolean b && b);
                        rows.add(row);
                    });
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("flags", rows);
                    model.put("hasFlags", !rows.isEmpty());
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                .register("studio.flagsSet", params -> {
                    studioEdit.requireEdit(params);
                    Object value = params.get("value");
                    studio.setFlag(str(params, "name"),
                            value == null ? "" : String.valueOf(value),
                            str(params, "type"), actorOf(params));
                    return Map.of("saved", true);
                })
                .register("studio.flagsRemove", params -> {
                    studioEdit.requireEdit(params);
                    studio.removeFlag(str(params, "name"), actorOf(params));
                    return Map.of("removed", true);
                });
    }

    /**
     * The data browser (docs/analytics-experience.md; Track J4): paginated rows of a chosen
     * table, opt-in via {@code tesseraql.studio.dataBrowser.enabled}, with PK-scoped row
     * editing and the CSV export of the current view. The table is validated against the live
     * catalog (no injection); a query error surfaces as a message rather than a 500.
     */
    private void dataBrowser(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.data", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("enabled", studioData.isEnabled());
                    if (!studioData.isEnabled()) {
                        return model;
                    }
                    // The datasource is a browsing dimension (docs/analytics-experience.md
                    // track 1): every declared datasource is browsable, validated by
                    // membership like the table name; editing stays a main-only affordance.
                    String datasource = StudioDataService.normalizeDatasource(
                            str(params, "ds"));
                    model.put("datasources", studioData.datasourceNames());
                    model.put("datasource", datasource);
                    model.put("exportMax", studioData.exportLimit());
                    String table = str(params, "table");
                    try {
                        model.put("tables", studioData.tables(datasource));
                    } catch (RuntimeException ex) {
                        model.put("tables", java.util.List.of());
                        model.put("error", ex.getMessage());
                        return model;
                    }
                    if (table == null) {
                        return model;
                    }
                    // Sort/filter state is echoed to the model so the form + prev/next/export
                    // links keep it; the service validates the columns against the real table.
                    String sortColumn = str(params, "sort");
                    String sortDir = dataSortDir(params);
                    model.put("sortColumn", sortColumn == null ? "" : sortColumn);
                    model.put("sortDir", sortDir);
                    String combinator = dataCombinator(params);
                    model.put("combinator", combinator);
                    // Up to DATA_FILTER_SLOTS conditions from indexed slots fcN/foN/fvN,
                    // combined by the combinator (AND/OR) - the one parse of the filter
                    // grammar, shared with the CSV export so the exported rows are exactly
                    // the rows this page shows.
                    java.util.List<StudioSupport.FilterSlot> slots = dataFilterSlots(params);
                    java.util.List<StudioDataService.FilterCond> filters = slots.stream()
                            .map(StudioSupport.FilterSlot::cond)
                            .filter(java.util.Objects::nonNull).toList();
                    java.util.List<Map<String, Object>> filterRows = slots.stream()
                            .map(StudioSupport.FilterSlot::row).toList();
                    model.put("filterRows", filterRows);
                    // Whether any filter/sort is active — drives the "Clear filters" control
                    // and keeps the filter disclosure open. The count labels the summary.
                    model.put("hasFilters", !filters.isEmpty() || sortColumn != null);
                    model.put("filterCount", filters.size());
                    model.put("queryBase", dataQueryBase(datasource, table, combinator,
                            sortColumn, sortDir, filterRows));
                    // Row editing (Track J4): the edit affordance appears only when
                    // the editor opt-in, the caller's edit permission, AND a primary
                    // key all line up; links carry the PK columns and the row's values.
                    // Non-main data is derived data, so the editor never leaves main.
                    boolean rowEditable = "main".equals(datasource)
                            && studioData.isEditEnabled()
                            && studioEdit.canEdit(params);
                    try {
                        int page = parseIndex(params.get("page"));
                        StudioDataService.DataPage data = studioData.browse(datasource,
                                table, page < 0 ? 0 : page, sortColumn, sortDir,
                                combinator, filters);
                        model.put("table", data.table());
                        model.put("columns", data.columns());
                        // Parallel to `columns` (hc-briefs.md brief 7): numeric columns
                        // render data-numeric so the kit end-aligns them.
                        model.put("numeric", data.numeric());
                        // Decision-contract overlay (docs/decision-tables.md): when the
                        // browsed table backs a table-backed decision, each mapped
                        // column's role, keyed by the displayed column name — a map
                        // PARALLEL to `columns` (whose shape the filter selects and the
                        // empty-state colspan reuse), matched case-insensitively against
                        // the names the decision declares — read through the shared memo,
                        // so a page render stops re-loading every decision set from disk.
                        Map<String, String> contracts = docCache
                                .columnContracts(data.table());
                        Map<String, String> columnContracts = new java.util.LinkedHashMap<>();
                        for (String column : data.columns()) {
                            contracts.forEach((mapped, role) -> {
                                if (mapped.equalsIgnoreCase(column)) {
                                    columnContracts.put(column, role);
                                }
                            });
                        }
                        model.put("columnContracts", columnContracts);
                        java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                        for (java.util.List<String> values : data.rows()) {
                            java.util.List<Map<String, Object>> cells = new java.util.ArrayList<>();
                            for (String value : values) {
                                Map<String, Object> cell = new java.util.LinkedHashMap<>();
                                cell.put("value", value == null ? "" : value);
                                cell.put("isNull", value == null);
                                cells.add(cell);
                            }
                            Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("cells", cells);
                            rows.add(row);
                        }
                        model.put("rows", rows);
                        model.put("rowCount", rows.size());
                        model.put("page", data.page());
                        model.put("hasNext", data.hasNext());
                        model.put("hasPrev", data.page() > 0);
                        model.put("nextPage", data.page() + 1);
                        model.put("prevPage", data.page() - 1);
                        if (rowEditable) {
                            model.put("editHrefs", editHrefs(data.table(), data.columns(),
                                    data.rows(),
                                    studioData.primaryKey(data.table())));
                        }
                    } catch (RuntimeException ex) {
                        model.put("error", ex.getMessage());
                    }
                    model.put("editEnabled", rowEditable);
                    model.put("updated", params.get("updated"));
                    return model;
                })
                // Data browser CSV export: the current view (table + filter + sort) as CSV,
                // capped at the scan limit. Served as a file download by the export route.
                // Row edit (Track J4): PK-scoped single-row form + UPDATE, under the
                // row-editor opt-in + the edit atom + an explicit confirm + the audit trail.
                .register("studio.data.editForm", params -> {
                    studioEdit.requireEdit(params);
                    String table = String.valueOf(params.get("table"));
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("table", table);
                    Map<String, String> rowKey = dataRowKey(params);
                    try {
                        StudioDataService.RowView row = studioData.row(table, rowKey);
                        model.put("pkColumns", row.pkColumns());
                        model.put("fields", row.fields());
                        model.put("keyPairs", rowKey.entrySet().stream()
                                .map(e -> Map.of("column", e.getKey(),
                                        "value", e.getValue()))
                                .toList());
                    } catch (RuntimeException ex) {
                        model.put("error", ex.getMessage());
                    }
                    return model;
                })
                .register("studio.data.update", params -> {
                    studioEdit.requireEdit(params);
                    requireExplicitConfirm(params, "Row edits");
                    String table = String.valueOf(params.get("table"));
                    Map<String, String> changes = new java.util.LinkedHashMap<>();
                    for (int i = 0; i < DATA_EDIT_SLOTS; i++) {
                        String column = str(params, "cn" + i);
                        if (column == null
                                || params.get("cs" + i) == null) {
                            continue;
                        }
                        Object value = params.get("cv" + i);
                        changes.put(column, value == null ? "" : String.valueOf(value));
                    }
                    Map<String, String> rowKey = dataRowKey(params);
                    try {
                        studioData.updateRow(table, rowKey, changes);
                    } catch (IllegalArgumentException | IllegalStateException ex) {
                        throw new io.tesseraql.core.error.TqlException(ROW_EDIT_REJECTED,
                                ex.getMessage());
                    }
                    // Audit the row identity and the columns touched — never the values
                    // (the browser may hold sensitive business data).
                    studio.recordDataEdit(actorOf(params), table + " ["
                            + String.join(", ", rowKey.entrySet().stream()
                                    .map(e -> e.getKey() + "=" + e.getValue()).toList())
                            + "] set " + String.join(", ", changes.keySet()));
                    return Map.of("updated", table);
                })
                .register("studio.data.export", params -> {
                    if (!studioData.isEnabled()) {
                        return Map.of("csv", "# The data browser is disabled.\r\n");
                    }
                    try {
                        return Map.of("csv", studioData.exportCsv(
                                StudioDataService.normalizeDatasource(str(params, "ds")),
                                str(params, "table"),
                                str(params, "sort"),
                                dataSortDir(params), dataCombinator(params),
                                dataFilters(params)));
                    } catch (RuntimeException ex) {
                        return Map.of("csv", "# " + ex.getMessage() + "\r\n");
                    }
                });
    }

    /**
     * The browse page's row-edit links, parallel to its rows (Track J4): each carries the PK
     * columns and the row's values; a null in a key cell drops that row's link, and a table
     * whose primary key is absent from the shown columns links nothing.
     */
    private static java.util.List<String> editHrefs(String table, java.util.List<String> columns,
            java.util.List<java.util.List<String>> rows, java.util.List<String> pk) {
        java.util.List<Integer> indexes = new java.util.ArrayList<>();
        for (String column : pk) {
            int at = -1;
            for (int i = 0; i < columns.size(); i++) {
                if (columns.get(i).equalsIgnoreCase(column)) {
                    at = i;
                    break;
                }
            }
            indexes.add(at);
        }
        boolean complete = !pk.isEmpty() && !indexes.contains(-1);
        java.util.List<String> hrefs = new java.util.ArrayList<>();
        for (java.util.List<String> values : rows) {
            String href = null;
            if (complete) {
                StringBuilder link = new StringBuilder(
                        "/_tesseraql/studio/ui/data/edit?table=" + urlEncode(table));
                boolean ok = true;
                for (int i = 0; i < pk.size(); i++) {
                    String value = values.get(indexes.get(i));
                    if (value == null) {
                        ok = false;
                        break;
                    }
                    link.append("&k").append(i).append('=').append(urlEncode(pk.get(i)));
                    link.append("&v").append(i).append('=').append(urlEncode(value));
                }
                href = ok ? link.toString() : null;
            }
            hrefs.add(href);
        }
        return hrefs;
    }

    /**
     * The i18n message editor (governance/authoring): a key × locale table over the app's
     * {@code messages/<locale>.yml} catalogs, flagging missing translations; the set action
     * upserts one translation (edit-gated + audited). The message resolver and client catalog
     * read {@code messages/} live, so an edit is served immediately.
     */
    private void messagesEditor(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.messages", params -> {
                    Map<String, Map<String, String>> catalogs = studio.messageCatalogs();
                    java.util.List<String> locales = new java.util.ArrayList<>(
                            catalogs.keySet());
                    java.util.TreeSet<String> keys = new java.util.TreeSet<>();
                    catalogs.values().forEach(entries -> keys.addAll(entries.keySet()));
                    java.util.List<Map<String, Object>> rows = new java.util.ArrayList<>();
                    int missing = 0;
                    for (String key : keys) {
                        java.util.List<Map<String, Object>> cells = new java.util.ArrayList<>();
                        for (String locale : locales) {
                            String value = catalogs.get(locale).get(key);
                            boolean isMissing = value == null || value.isBlank();
                            if (isMissing) {
                                missing++;
                            }
                            Map<String, Object> cell = new java.util.LinkedHashMap<>();
                            cell.put("locale", locale);
                            cell.put("value", value == null ? "" : value);
                            cell.put("missing", isMissing);
                            cells.add(cell);
                        }
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("key", key);
                        row.put("cells", cells);
                        rows.add(row);
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("locales", locales);
                    model.put("rows", rows);
                    model.put("keyCount", keys.size());
                    model.put("localeCount", locales.size());
                    model.put("missingCount", missing);
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                .register("studio.messageSet", params -> {
                    studioEdit.requireEdit(params);
                    Object value = params.get("value");
                    studio.setMessage(str(params, "locale"), str(params, "key"),
                            value == null ? "" : String.valueOf(value), actorOf(params));
                    return Map.of("saved", true);
                });
    }

    /** Snippet builders: migrations, SQL, validations, decisions. */
    private void snippetBuilders(io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                // New migration page (Studio backlog: migration authoring): the form shows the
                // next versioned number for the main datasource; the create writes a Flyway
                // migration under db/…/migration and the result links to the source editor.
                .register("studio.migration.new", params -> {
                    boolean canEdit = studioEdit.canEdit(params);
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    putEditFlags(model, canEdit);
                    model.put("nextVersion", studio.nextMigrationVersion("main", null));
                    // The DDL builder's table dropdown is populated from the schema overlay.
                    io.tesseraql.studio.DocService migrationDoc = new io.tesseraql.studio.DocService(
                            manifest);
                    java.util.List<String> tables = migrationDoc.tableNames();
                    model.put("tables", tables);
                    model.put("hasTables", !tables.isEmpty());
                    // A schema baseline enables generating a migration from the schema diff.
                    model.put("hasSchemaBaseline", migrationDoc.hasSchemaBaseline());
                    return model;
                })
                // Cascade for the DDL builder: a chosen table's columns, for the index
                // builder's column autocomplete (rendered as datalist options).
                .register("studio.migration.columns",
                        params -> Map.of("columns", new io.tesseraql.studio.DocService(
                                manifest).columnNames(
                                        params.get("table") == null
                                                ? null
                                                : String.valueOf(params.get("table")))))
                // Generate a migration from the schema diff (baseline schema.json vs current):
                // captures direct database changes back into a migration. Pure generation.
                .register("studio.migration.diff", params -> {
                    io.tesseraql.studio.DocService diffDoc = new io.tesseraql.studio.DocService(
                            manifest);
                    String ddl;
                    if (!diffDoc.hasSchemaBaseline()) {
                        ddl = "-- No schema baseline. Copy .tesseraql/docs/schema.json to "
                                + "schema.baseline.json, then regenerate to see changes since.";
                    } else if (diffDoc.schemaBaselineCorrupt()) {
                        // A corrupt baseline used to read as "no changes" — an operator
                        // would trust the database matched and generate an empty migration.
                        ddl = "-- The schema baseline (schema.baseline.json) is unreadable;"
                                + " re-capture it before generating a migration.";
                    } else {
                        String diff = diffDoc.schemaDiffDdl();
                        ddl = diff == null || diff.isBlank()
                                ? "-- No schema changes since the baseline."
                                : diff;
                    }
                    return Map.of("ddl", ddl);
                })
                // The 2-way SQL builder (migration authoring follow-on): generate a route's
                // select/insert/update/delete 2-way SQL for a chosen table + operation, from
                // the schema overlay. Pure generation — no side effect.
                .register("studio.sqlBuilder.new", params -> {
                    boolean canEdit = studioEdit.canEdit(params);
                    java.util.List<String> tables = new io.tesseraql.studio.DocService(
                            manifest).tableNames();
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    putEditFlags(model, canEdit);
                    model.put("tables", tables);
                    model.put("hasTables", !tables.isEmpty());
                    return model;
                })
                // Cascade: a chosen table's columns for the by-column filter dropdown.
                .register("studio.sqlBuilder.columns",
                        params -> Map.of("columns", new io.tesseraql.studio.DocService(
                                manifest).columnNames(
                                        params.get("table") == null
                                                ? null
                                                : String.valueOf(params.get("table")))))
                .register("studio.sqlBuilder.build", params -> {
                    String tableName = params.get("table") == null
                            ? null
                            : String.valueOf(params.get("table"));
                    io.tesseraql.yaml.scaffold.CatalogSchema.Table table = new io.tesseraql.studio.DocService(
                            manifest).tableByName(tableName);
                    String sql;
                    if (table == null) {
                        sql = "-- No such table in the schema overlay: " + tableName;
                    } else {
                        String generated = io.tesseraql.studio.SqlBuilder.generate(table,
                                String.valueOf(params.get("operation")),
                                params.get("column") == null
                                        ? null
                                        : String.valueOf(params.get("column")));
                        sql = generated.isEmpty() ? "-- Unknown operation." : generated;
                    }
                    return Map.of("sql", sql);
                })
                // Validation rule builder: generate a route's validate: YAML for one rule from
                // a chosen operation (required/min/max/range/equals/one-of/expression/sql).
                // Pure text generation to copy into the route — no side effect.
                .register("studio.validationBuilder",
                        params -> Map.of("editable",
                                studioEdit.canEdit(params),
                                // Input-level constraints may already belong to a field
                                // domain (docs/field-domains.md); the builder page
                                // points at them before a cross-field rule is written.
                                "domains", studio.domainNames(),
                                // Shared rules with their contracts: the builder offered
                                // only inline rules, so an author generating a SQL rule
                                // got a copy of one that already existed.
                                "sharedRules", studio.sharedRules()))
                .register("studio.validationBuilder.build", params -> Map.of("snippet",
                        io.tesseraql.studio.ValidationRuleBuilder.generate(
                                str(params, "operation"), str(params, "source"),
                                str(params, "field"), str(params, "value"),
                                str(params, "value2"), str(params, "id"),
                                str(params, "code"), str(params, "message"),
                                str(params, "when"),
                                studio.sharedRules().stream()
                                        .filter(r -> r.name().equals(str(params, "value")))
                                        .findFirst()
                                        .map(io.tesseraql.studio.StudioService.SharedRule::binds)
                                        .orElse(java.util.Map.of()))))
                // Decide-snippet builder (docs/decision-tables.md): generate a route's
                // decide: block for one declared decision — the validation builder's
                // shape applied to the other shared definition. Pure text generation to
                // copy into the route; the contract travels with the name because a
                // reference must wire the inputs exactly (TQL-DECISION-4706).
                .register("studio.decisionBuilder",
                        params -> Map.of("decisions", studio.sharedDecisions()))
                .register("studio.decisionBuilder.build", params -> {
                    java.util.Optional<io.tesseraql.studio.StudioService.SharedDecision> match = studio
                            .sharedDecisions().stream()
                            .filter(d -> d.name().equals(str(params, "decision")))
                            .findFirst();
                    return Map.of("snippet", io.tesseraql.studio.DecideSnippetBuilder
                            .generate(str(params, "decision"),
                                    match.map(
                                            io.tesseraql.studio.StudioService.SharedDecision::inputs)
                                            .orElse(java.util.List.of()),
                                    match.map(
                                            io.tesseraql.studio.StudioService.SharedDecision::dated)
                                            .orElse(false)));
                })
        // Decision rows grid (docs/decision-tables.md "Studio"): a YAML-backed
        // decision's rows as a table-shaped editor, saved through the draft flow
        // (routeFormSave's persistence contract) after a parse + compile check.
        ;
    }

    /** Decisions, calendars and jobs. */
    private void decisionsCalendarsJobs(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.decisions.view", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("editable", studioEdit.canEdit(params));
                    model.put("decisions", studio.sharedDecisions());
                    String name = str(params, "name");
                    model.put("grid", name == null ? null : studio.decisionGrid(name));
                    model.put("saved", params.get("saved") != null);
                    return model;
                })
                .register("studio.decisions.save", params -> {
                    studioEdit.requireEdit(params);
                    String name = String.valueOf(params.get("name"));
                    java.util.List<io.tesseraql.studio.StudioService.DecisionColumn> columns = new java.util.ArrayList<>();
                    for (int j = 0; j < io.tesseraql.studio.StudioService.DECISION_GRID_COLUMNS; j++) {
                        String key = str(params, "c" + j + "key");
                        if (key == null) {
                            continue;
                        }
                        java.util.List<String> cells = new java.util.ArrayList<>();
                        for (int i = 0; i < io.tesseraql.studio.StudioService.DECISION_GRID_ROWS; i++) {
                            cells.add(str(params, "r" + i + "c" + j));
                        }
                        columns.add(new io.tesseraql.studio.StudioService.DecisionColumn(
                                key, str(params, "c" + j + "kind"), cells));
                    }
                    java.util.Set<Integer> deletes = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < io.tesseraql.studio.StudioService.DECISION_GRID_ROWS; i++) {
                        if (params.get("d" + i) != null) {
                            deletes.add(i);
                        }
                    }
                    studio.saveDecisionRows(name, columns, deletes, actorOf(params));
                    return Map.of("saved", name);
                })
                // Business-day calendars (docs/jobs.md, Studio): list + month grid
                // preview + form editing of weekend/dates through the draft flow.
                // Table-backed holiday rows are read on the MAIN datasource for the
                // preview; the rows themselves belong to the data browser.
                .register("studio.calendars.view", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("editable", studioEdit.canEdit(params));
                    List<io.tesseraql.studio.StudioService.CalendarSummary> declared = studio
                            .calendars();
                    model.put("calendars", declared);
                    model.put("hasCalendars", !declared.isEmpty());
                    String name = str(params, "name");
                    model.put("saved", params.get("saved") != null);
                    if (name == null) {
                        model.put("grid", null);
                        model.put("selected", null);
                        return model;
                    }
                    io.tesseraql.studio.StudioService.CalendarSummary selected = declared
                            .stream().filter(c -> c.name().equals(name)).findFirst()
                            .orElse(null);
                    model.put("selected", selected);
                    // The edit card's draft-aware state (studio-ux-refresh slice 6):
                    // click-to-toggle edits accumulate on the pending draft, so the
                    // card must render what the DRAFT holds, not the served source.
                    model.put("edit", selected == null || selected.tableBacked()
                            ? null
                            : studio.calendarEditState(name));
                    java.util.Set<java.time.LocalDate> tableHolidays = null;
                    if (selected != null && selected.tableBacked()) {
                        io.tesseraql.yaml.calendar.Calendars loaded = io.tesseraql.yaml.calendar.Calendars
                                .load(appHome, new io.tesseraql.yaml.SimpleYamlParser());
                        io.tesseraql.yaml.model.CalendarsDocument.Calendar calendar = loaded
                                .calendars().get(name);
                        try (java.sql.Connection connection = dataSource
                                .getConnection()) {
                            tableHolidays = io.tesseraql.yaml.calendar.Calendars
                                    .readHolidays(connection, name,
                                            calendar.holidays().source());
                        } catch (Exception ex) {
                            LOG.warn("Calendar '{}' holiday read failed for the"
                                    + " preview", name, ex);
                        }
                    }
                    Integer dayOfMonth = null;
                    String rawDay = str(params, "dayOfMonth");
                    if (rawDay != null && rawDay.matches("[0-9]{1,2}")) {
                        dayOfMonth = Integer.parseInt(rawDay);
                    }
                    model.put("grid", studio.calendarMonth(name, str(params, "month"),
                            dayOfMonth, str(params, "shift"), tableHolidays));
                    return model;
                })
                .register("studio.calendars.save", params -> {
                    studioEdit.requireEdit(params);
                    String name = String.valueOf(params.get("name"));
                    java.util.List<String> weekend = new java.util.ArrayList<>();
                    for (String day : java.util.List.of("monday", "tuesday",
                            "wednesday", "thursday", "friday", "saturday", "sunday")) {
                        if (params.get("weekend_" + day) != null) {
                            weekend.add(day);
                        }
                    }
                    java.util.List<String> dates = new java.util.ArrayList<>();
                    String rawDates = str(params, "dates");
                    if (rawDates != null) {
                        for (String line : rawDates.split("[\\r\\n,]+")) {
                            dates.add(line);
                        }
                    }
                    studio.saveCalendar(name, weekend, dates, actorOf(params));
                    return Map.of("saved", name);
                })
                // Click-to-toggle holidays (studio-ux-refresh slice 6): the edit
                // card's hc-calendar posts the picked date here; the toggle rides
                // the same validated draft flow as the form save.
                .register("studio.calendars.toggle", params -> {
                    studioEdit.requireEdit(params);
                    String calName = String.valueOf(params.get("name"));
                    studio.toggleCalendarHoliday(calName, str(params, "date"),
                            actorOf(params));
                    return Map.of("saved", calName);
                })
                // Job operational policies (docs/jobs.md, Studio): trigger + calendar
                // qualifiers + overlap/sla as a structured form through the draft flow.
                .register("studio.jobs.view", params -> {
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("editable", studioEdit.canEdit(params));
                    List<Map<String, Object>> declared = new java.util.ArrayList<>();
                    for (JobFile jobFile : manifest.jobs()) {
                        Map<String, Object> row = new java.util.LinkedHashMap<>();
                        row.put("id", jobFile.definition().id());
                        row.put("trigger", io.tesseraql.yaml.model.TriggerSpec
                                .describe(jobFile.definition().trigger()));
                        // The jobs list (studio-ux-refresh slice 6): calendar and
                        // the next date the calendar lets a firing count — the same
                        // decision helper the scheduler and ops console use.
                        io.tesseraql.yaml.model.TriggerSpec.Schedule schedule = jobFile
                                .definition().trigger() == null
                                        ? null
                                        : jobFile.definition().trigger().schedule();
                        row.put("calendar", schedule == null
                                || schedule.calendar() == null
                                || schedule.calendar().isBlank()
                                        ? null
                                        : schedule.calendar());
                        row.put("next", calendarDecisions.nextCounting(jobFile,
                                java.time.LocalDate.now()));
                        declared.add(row);
                    }
                    declared.sort(java.util.Comparator
                            .comparing(row -> String.valueOf(row.get("id"))));
                    model.put("jobs", declared);
                    model.put("hasJobs", !declared.isEmpty());
                    model.put("calendars", studio.calendars());
                    model.put("saved", params.get("saved") != null);
                    String name = str(params, "name");
                    model.put("form",
                            name == null ? null : studio.jobPolicyForm(name));
                    return model;
                })
                .register("studio.jobs.save", params -> {
                    studioEdit.requireEdit(params);
                    String jobId = String.valueOf(params.get("name"));
                    studio.saveJobPolicies(jobId, str(params, "cron"),
                            str(params, "fixedDelay"), str(params, "calendar"),
                            str(params, "runOn"), str(params, "dayOfMonth"),
                            str(params, "shift"), str(params, "after"),
                            str(params, "overlap"), str(params, "slaCompleteBy"),
                            str(params, "slaRunningLongerThan"), actorOf(params));
                    return Map.of("saved", jobId);
                });
    }

    /** Schema capture and migration execution. */
    private void schemaAndMigrationRuns(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.migration.create", params -> {
                    studioEdit.requireEdit(params);
                    String datasource = params.get("datasource") == null
                            ? "main"
                            : String.valueOf(params.get("datasource"));
                    String vendor = params.get("vendor") == null
                            ? null
                            : String.valueOf(params.get("vendor"));
                    boolean repeatable = "repeatable"
                            .equals(String.valueOf(params.get("kind")));
                    String description = params.get("description") == null
                            ? null
                            : String.valueOf(params.get("description"));
                    String ddl = params.get("ddl") == null
                            ? null
                            : String.valueOf(params.get("ddl"));
                    io.tesseraql.studio.StudioService.MigrationResult result = studio
                            .createMigration(datasource, vendor, repeatable, description,
                                    ddl, false, actorOf(params));
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("created", true);
                    model.put("path", result.path());
                    model.put("filename", result.filename());
                    model.put("version", result.version());
                    model.put("repeatable", result.repeatable());
                    model.put("editorUrl", sourceEditorUrl(result.path()));
                    return model;
                })
                // Refresh schema (docs/studio-schema-lifecycle.md): live introspection
                // over the runtime's own pools - the same CatalogIntrospector Scaffold
                // uses - persisted as the SchemaOverlay envelope the build-time schema
                // goal emits, so the SQL/DDL builders and docs pages stop depending on
                // an out-of-band goal run. Edit-gated, audited.
                .register("studio.schemaRefresh", params -> {
                    studioEdit.requireEdit(params);
                    java.util.Map<String, io.tesseraql.yaml.scaffold.CatalogSchema> introspected = new java.util.LinkedHashMap<>();
                    io.tesseraql.yaml.scaffold.CatalogIntrospector introspector = new io.tesseraql.yaml.scaffold.CatalogIntrospector();
                    for (Map.Entry<String, com.zaxxer.hikari.HikariDataSource> entry : dataSources
                            .entrySet()) {
                        try (java.sql.Connection connection = entry.getValue()
                                .getConnection()) {
                            introspected.put(entry.getKey(),
                                    introspector.introspect(connection));
                        } catch (java.sql.SQLException ex) {
                            throw new io.tesseraql.core.error.TqlException(
                                    new io.tesseraql.core.error.TqlErrorCode(
                                            io.tesseraql.core.error.TqlDomain.APP, 5204),
                                    "Schema introspection failed for datasource '"
                                            + entry.getKey() + "': " + ex.getMessage());
                        }
                    }
                    studio.refreshSchema(introspected, actorOf(params));
                    // schema.json changed in place, outside any route reload: the shared
                    // memo's table list must not keep serving the pre-refresh overlay.
                    docCache.invalidate();
                    return java.util.Map.of("refreshed", true,
                            "datasources", introspected.size());
                })
                // Capture baselines (docs/studio-schema-lifecycle.md): both diff anchors
                // in one action - schema.baseline.json copies the sidecar, and the
                // OpenAPI baseline persists the live document (no openapi.json file
                // exists at runtime). Edit-gated, audited.
                .register("studio.baselineCapture", params -> {
                    studioEdit.requireEdit(params);
                    // A fresh DocService reads the manifest live, like the migration
                    // providers above - the OpenAPI document is generated, not cached.
                    studio.captureBaselines(
                            new io.tesseraql.studio.DocService(manifest).openApiJson(),
                            actorOf(params));
                    return java.util.Map.of("captured", true);
                })
                // Migrate now (roadmap Phase 42): applies the app's pending migrations to
                // the dev datasource on demand, closing the schema -> scaffold -> serve
                // loop without a process bounce. Same Flyway path as startup (main set +
                // tenant pools + named per-datasource sets); edit-gated, confirm-gated
                // like apply, and recorded to the audit trail.
                .register("studio.migration.migrate", params -> {
                    studioEdit.requireEdit(params);
                    studioEdit.requireConfirm(
                            "true".equals(String.valueOf(params.get("confirm"))));
                    int applied = AppMigrations.migrate(appName, appHome,
                            manifest.config(), dataSource, tenantDataSources,
                            dataSources::get);
                    studio.recordMigrationRun(actorOf(params), "db/migration");
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("applied", applied);
                    return model;
                })
                // Dry-run a migration's DDL against the sandbox (auto-rollback) before it
                // lands — gated like the test runner (opt-in enabled); Postgres only.
                .register("studio.migration.dryRun", params -> {
                    String path = String.valueOf(params.get("path"));
                    Object content = params.get("content");
                    io.tesseraql.studio.StudioService.DryRunResult result;
                    if (!studioTests.isEnabled()) {
                        result = io.tesseraql.studio.StudioService.DryRunResult.declined(
                                "The Studio test runner is disabled (set "
                                        + "tesseraql.studio.testRunner.enabled).");
                    } else {
                        result = studio.dryRunMigration(path,
                                content == null ? null : String.valueOf(content),
                                studioTests::dryRunDdl);
                    }
                    Map<String, Object> model = new java.util.LinkedHashMap<>();
                    model.put("ran", result.ran());
                    model.put("ok", result.ok());
                    model.put("message", result.message());
                    return model;
                })
                // Form-driven DDL builder (migration authoring): generate standard DDL for a
                // common operation, dropped into the New migration page's DDL field. Pure
                // generation — no side effect — so no edit gate beyond the endpoint's auth.
                .register("studio.migration.build", params -> {
                    java.util.function.Function<String, String> field = key -> params
                            .get(key) == null ? null : String.valueOf(params.get(key));
                    String operation = String.valueOf(params.get("operation"));
                    String ddl = switch (operation) {
                        case "add-column" -> io.tesseraql.studio.MigrationDdl.addColumn(
                                field.apply("table"), field.apply("column"),
                                field.apply("type"),
                                !"true".equals(field.apply("notNull")),
                                field.apply("default"));
                        case "create-index" -> io.tesseraql.studio.MigrationDdl.createIndex(
                                field.apply("table"), field.apply("columns"),
                                "true".equals(field.apply("unique")), field.apply("name"));
                        case "create-table" -> io.tesseraql.studio.MigrationDdl.createTable(
                                field.apply("table"), field.apply("columnLines"),
                                field.apply("primaryKey"));
                        default -> throw new io.tesseraql.core.error.TqlException(
                                new io.tesseraql.core.error.TqlErrorCode(
                                        io.tesseraql.core.error.TqlDomain.STUDIO, 4224),
                                "Unknown DDL operation: " + operation);
                    };
                    return Map.of("ddl", ddl);
                });
    }

    /** Apply, preview, tests, scaffolding and the audit. */
    private void applyPreviewTestScaffoldAudit(
            io.tesseraql.core.service.ServiceProviders serviceProviders) {
        serviceProviders
                .register("studio.apply", params -> {
                    studioEdit.requireEdit(params);
                    String path = String.valueOf(params.get("path"));
                    // force=true overwrites a concurrently changed source (backlog D5).
                    boolean force = "true".equals(String.valueOf(params.get("force")));
                    boolean confirm = "true".equals(String.valueOf(params.get("confirm")));
                    // When confirm-before-apply is on, require an explicit acknowledgment
                    // (the conflict force checkbox counts as one).
                    studioEdit.requireConfirm(confirm || force);
                    // The caller is recorded to the audit trail (backlog D6).
                    studio.applyDraft(path, force, actorOf(params));
                    reloader.reload();
                    return Map.of("applied", path);
                })
                .register("studio.preview", params -> {
                    String path = String.valueOf(params.get("path"));
                    Object content = params.get("content");
                    return io.tesseraql.studio.StudioViews.preview(studio.preview(path,
                            content == null ? null : String.valueOf(content)));
                })
                .register("studio.render", params -> {
                    String path = String.valueOf(params.get("path"));
                    Object content = params.get("content");
                    Object sample = params.get("sampleModel");
                    // Live data: run the route's query through the A2 sandbox for real rows,
                    // gated like the test runner (writable Studio + opt-in enabled).
                    boolean live = "true".equals(String.valueOf(params.get("live")));
                    io.tesseraql.studio.StudioService.RowSource rows = live
                            && studioTests.isEnabled() ? studioTests::liveRows : null;
                    return io.tesseraql.studio.StudioViews.render(studio.render(path,
                            content == null ? null : String.valueOf(content),
                            sample == null ? null : String.valueOf(sample), rows,
                            studioMask, studioPdf),
                            io.tesseraql.pipeline.BasePath
                                    .of(context.beans()));
                })
                .register("studio.runTests",
                        params -> studioTests
                                .runForPath(String.valueOf(params.get("path"))))
                .register("studio.scaffold.tables",
                        params -> io.tesseraql.studio.StudioViews.scaffoldTables(
                                studioScaffold.tables(), studioScaffold.isEnabled()))
                .register("studio.scaffold.preview",
                        params -> io.tesseraql.studio.StudioViews.scaffoldPreview(
                                studioScaffold.preview(
                                        String.valueOf(params.get("table")))))
                .register("studio.scaffold.apply", params -> {
                    studioEdit.requireEdit(params);
                    Object result = io.tesseraql.studio.StudioViews.scaffoldResult(
                            studioScaffold.apply(String.valueOf(params.get("table")),
                                    "true".equals(String.valueOf(params.get("force"))),
                                    actorOf(params)));
                    // The instant loop (roadmap Phase 42): the scaffolded routes mount
                    // right away — no restart between apply and serving.
                    reloader.reload();
                    return result;
                })
                .register("studio.discard", params -> {
                    studioEdit.requireEdit(params);
                    String path = String.valueOf(params.get("path"));
                    studio.deleteDraft(path);
                    return Map.of("discarded", path);
                })
                // Studio Drafts bulk actions: apply every clean draft (conflicts skipped) or
                // discard them all — the batch management the Explorer drafts-in-tree can't do.
                // Edit-gated like studio.apply/scaffold.apply; apply reloads routes after.
                .register("studio.draftsApplyAll", params -> {
                    studioEdit.requireEdit(params);
                    io.tesseraql.studio.StudioService.BulkApplyResult result = studio
                            .applyAllDrafts(actorOf(params));
                    reloader.reload();
                    return Map.of("applied", result.applied(), "skipped", result.skipped(),
                            "needsRestart", result.needsRestart());
                })
                .register("studio.draftsDiscardAll", params -> {
                    studioEdit.requireEdit(params);
                    return Map.of("discarded", studio.discardAllDrafts());
                })
                .register("studio.drafts", params -> {
                    String q = params.get("q") == null
                            ? null
                            : String.valueOf(params.get("q"));
                    Map<String, Object> model = io.tesseraql.studio.StudioViews
                            .drafts(studio.drafts(), q);
                    // The bulk apply/discard actions follow the caller's edit permission.
                    model.put("editable", studioEdit.canEdit(params));
                    return model;
                })
                .register("studio.audit", params -> {
                    // Filter the whole log, then sort + page it (H5 filter + I3 paging + I2 sort).
                    String q = params.get("q") == null
                            ? null
                            : String.valueOf(params.get("q"));
                    String sort = params.get("sort") == null
                            ? null
                            : String.valueOf(params.get("sort"));
                    String dir = params.get("dir") == null
                            ? null
                            : String.valueOf(params.get("dir"));
                    return io.tesseraql.studio.StudioViews.audit(
                            studio.auditPage(q, sort, dir, parsePage(params.get("page")),
                                    io.tesseraql.studio.StudioViews.AUDIT_PAGE_SIZE),
                            q, sort, dir);
                });
    }

    /**
     * The text an editor surface reads for {@code path}: its pending draft when one exists,
     * otherwise the saved source — {@code null} when neither does. The draft-preferring read every
     * authoring surface makes, so a second edit sees the first.
     */
    private static String draftOrSource(io.tesseraql.studio.StudioService studio, String path) {
        String draft = studio.readDraft(path);
        return draft != null ? draft : studio.sourceIfExists(path);
    }

    /**
     * The chrome the three text editors share (source editor, mail composer, page builder): the
     * draft and the saved source read once, the colocated sample fixture, and the draft/conflict/
     * permission state their common apply-discard bar renders from. Each surface then adds only
     * what is its own — the composer's blocks, the builder's split, the source page's tools.
     *
     * @param path the app-relative file being edited
     * @param draft its pending draft, or null when none is saved
     * @param saved its source on disk, or null for a not-yet-applied new file
     * @param sampleModel the colocated {@code .sample.yml} fixture, or null
     * @param conflict whether applying the draft would overwrite a concurrently changed source
     * @param confirmApply whether an explicit acknowledgment is required before apply
     * @param canEdit whether the caller may edit (backlog D6)
     */
    private record EditorChrome(String path, String draft, String saved, String sampleModel,
            boolean conflict, boolean confirmApply, boolean canEdit) {

        /** Reads the chrome of {@code path} for the params' caller, one read per file. */
        static EditorChrome of(io.tesseraql.studio.StudioService studio, StudioEdit access,
                String path, Map<String, Object> params) {
            String draft = studio.readDraft(path);
            return new EditorChrome(path, draft, studio.sourceIfExists(path),
                    studio.sampleModel(path), draft != null && studio.draftConflicts(path),
                    access.confirmApply(), access.canEdit(params));
        }

        /** The text the surface edits — the draft-preferring read, over what was read already. */
        String text() {
            return draft != null ? draft : saved;
        }

        /** Whether an unsaved draft is what the surface is showing. */
        boolean hasDraft() {
            return draft != null;
        }

        /** The shared keys of an editor page model, for a surface to add its own to. */
        Map<String, Object> model() {
            Map<String, Object> model = new java.util.LinkedHashMap<>();
            model.put("path", path);
            model.put("hasDraft", hasDraft());
            model.put("source", text() == null ? "" : text());
            model.put("sampleModel", sampleModel == null ? "" : sampleModel);
            return applyTo(model);
        }

        /** Adds the action bar's own keys to a model built elsewhere (the source page's view). */
        Map<String, Object> applyTo(Map<String, Object> model) {
            // Warn when applying would overwrite a concurrently changed source (D5), require an
            // explicit acknowledgment when configured, and follow the caller's edit permission.
            model.put("conflict", conflict);
            model.put("confirmApply", confirmApply);
            putEditFlags(model, canEdit);
            return model;
        }
    }

    /**
     * Which visual authoring surface a template is eligible for, from a single parse of its text:
     * the page builder takes builder-shaped pages that are not mail (docs/page-builder.md D1), the
     * mail composer takes documents matching the {@code tql/email/*} block grammar
     * (docs/html-email.md D4) — anything else stays with the source editor.
     *
     * @param builder whether the page builder may open it
     * @param mailComposer whether the mail composer may open it
     * @param parts the builder's verbatim prefix/region/suffix split, when it parsed
     * @param composition the composer's parsed blocks, when they parsed
     */
    private record Eligibility(boolean builder, boolean mailComposer,
            java.util.Optional<io.tesseraql.studio.PageBuilder.Parts> parts,
            java.util.Optional<io.tesseraql.studio.MailComposer.Composition> composition) {

        /** Parses {@code text} (null tolerated) once for both surfaces. */
        static Eligibility of(String path, String text) {
            java.util.Optional<io.tesseraql.studio.PageBuilder.Parts> parts = io.tesseraql.studio.PageBuilder
                    .parse(text);
            java.util.Optional<io.tesseraql.studio.MailComposer.Composition> composition = io.tesseraql.studio.MailComposer
                    .parse(text);
            boolean html = path != null && path.endsWith(".html");
            return new Eligibility(html && parts.isPresent() && composition.isEmpty(),
                    html && composition.isPresent(), parts, composition);
        }
    }
}
