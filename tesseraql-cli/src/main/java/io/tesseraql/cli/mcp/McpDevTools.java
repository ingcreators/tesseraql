package io.tesseraql.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.tesseraql.cli.EmbeddedDbMarker;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.mcp.McpCallContext;
import io.tesseraql.mcp.McpPrompt;
import io.tesseraql.mcp.McpPromptResult;
import io.tesseraql.mcp.McpSchema;
import io.tesseraql.mcp.McpServer;
import io.tesseraql.mcp.McpTool;
import io.tesseraql.mcp.McpToolResult;
import io.tesseraql.operations.batch.JobExecution;
import io.tesseraql.operations.batch.JobRepository;
import io.tesseraql.operations.outbox.JdbcOutboxStore;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.CoverageThresholdResolver;
import io.tesseraql.report.DriverManagerDataSource;
import io.tesseraql.runtime.DataSources;
import io.tesseraql.runtime.DriverBackedDataSource;
import io.tesseraql.studio.StudioService;
import io.tesseraql.yaml.config.AppConfig;
import io.tesseraql.yaml.config.ResponseHeaderDefaults;
import io.tesseraql.yaml.config.SecurityDefaults;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldWriter;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import javax.sql.DataSource;

/**
 * Exposes TesseraQL's developer surfaces - manifest, sources, schema introspection, lint, tests,
 * coverage, ops status, scaffolding, and Studio drafts - as MCP tools, so an agent connected only
 * over MCP can scaffold a table-backed route and iterate until lint, tests, and coverage pass
 * without touching the filesystem directly (roadmap Phase 24).
 *
 * <p>One server spans the stack (docs/stack-architecture.md decision 19): the development tools run
 * on the developer's own machine against application homes they already hold on disk, so there is
 * no per-application audience to protect and a team building interlocking applications wants one
 * agent that can see all of them. Every tool therefore carries an {@code application} argument
 * whose description tells the model how to choose - an agent that guesses which application it is
 * editing is worse than one that has to be told - and read-only is a property of the server, not
 * of an application.
 *
 * <p>Every tool reuses the same framework services the CLI and Maven plugin use, so behavior is
 * identical to running them by hand. Write tools (scaffold, drafts) are gated: they apply through
 * the checksum-aware writer and Studio's draft/apply mechanism with the same app-home path
 * confinement, and are omitted entirely in read-only mode.
 */
public final class McpDevTools {

    private static final String VERSION = io.tesseraql.core.TesseraqlVersion.current();
    private static final TqlErrorCode BAD_ARGS = new TqlErrorCode(TqlDomain.MCP, 4002);
    private static final TqlErrorCode NO_DATASOURCE = new TqlErrorCode(TqlDomain.MCP, 5001);

    /** The applications this server spans, name to home, in the stack's stable order. */
    private final Map<String, Path> applications;
    private final boolean readOnly;
    /** Each application's expression-function set, name-keyed like {@link #applications}. */
    private final Map<String, ExpressionFunctions> functions;
    /** Each application's module classloader, name-keyed like {@link #applications}. */
    private final Map<String, ClassLoader> loaders;

    public McpDevTools(Map<String, Path> applications, boolean readOnly) {
        this(applications, readOnly,
                perApplication(applications, ExpressionFunctions.processDefault()),
                perApplication(applications, McpDevTools.class.getClassLoader()));
    }

    /**
     * As the two-argument form, with each application's own expression-function set and module
     * classloader (docs/module-scope.md).
     */
    public McpDevTools(Map<String, Path> applications, boolean readOnly,
            Map<String, ExpressionFunctions> functions, Map<String, ClassLoader> loaders) {
        if (applications.isEmpty()) {
            throw new IllegalArgumentException("The MCP dev-tool server needs at least one"
                    + " application.");
        }
        LinkedHashMap<String, Path> normalized = new LinkedHashMap<>();
        applications.forEach(
                (name, home) -> normalized.put(name, home.toAbsolutePath().normalize()));
        this.applications = Collections.unmodifiableMap(normalized);
        this.readOnly = readOnly;
        this.functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        this.loaders = Collections.unmodifiableMap(new LinkedHashMap<>(loaders));
    }

    /** The same value for every application, for the default-wiring constructor. */
    private static <V> Map<String, V> perApplication(Map<String, Path> applications, V value) {
        Map<String, V> shared = new LinkedHashMap<>();
        applications.keySet().forEach(name -> shared.put(name, value));
        return shared;
    }

    /**
     * Builds the MCP server with the read tools, plus the gated write tools and the Studio-copilot
     * prompt when writable (the prompt drives the write loop, so it is offered only in write mode).
     */
    public McpServer toServer() {
        McpServer.Builder builder = McpServer.builder("tesseraql-dev", VERSION)
                .instructions(instructions())
                .tools(readTools());
        if (!readOnly) {
            builder.tools(writeTools());
            builder.prompts(prompts());
        }
        return builder.build();
    }

    private String instructions() {
        return "TesseraQL developer tools, spanning "
                + (applications.size() == 1
                        ? "the application '" + soleName() + "'."
                        : applications.size() + " applications: " + names() + ". Every tool takes"
                                + " an 'application' argument naming which one it operates on;"
                                + " pass the one the developer's request concerns, and ask rather"
                                + " than guess when it is ambiguous.")
                + " Typical loop: scaffold_crud a table, then lint and test until both pass. Edit"
                + " files through draft_save -> draft_preview -> draft_apply (a draft only applies"
                + " if it compiles). All paths are app-home-relative and confined to the"
                + " application. schema_introspect, test, and ops_status use the application's"
                + " configured datasource unless you pass jdbcUrl/username/password. To build from"
                + " a plain-language request, start with the studio_copilot prompt, which walks"
                + " the describe -> draft -> preview -> apply loop.";
    }

    List<McpTool> readTools() {
        return List.of(manifestSummary(), sourceRead(), schemaIntrospect(), lint(), test(),
                opsStatus());
    }

    List<McpTool> writeTools() {
        return List.of(scaffoldCrud(), draftSave(), draftPreview(), draftApply());
    }

    List<McpPrompt> prompts() {
        return List.of(studioCopilot());
    }

    // ----- the application argument (decision 19) ---------------------------

    /**
     * A schema pre-seeded with the {@code application} argument every development tool carries.
     * Required when the server spans several applications - the description tells the model how to
     * choose - and optional with the obvious default when it holds exactly one.
     */
    private McpSchema schema() {
        McpSchema schema = McpSchema.object();
        return applications.size() > 1
                ? schema.required("application", "string", "which application this call operates"
                        + " on - one of: " + names() + ". Pass the application the developer's"
                        + " request concerns; ask rather than guess when it is ambiguous.")
                : schema.property("application", "string", "the application to operate on"
                        + " (default: '" + soleName() + "', the only application this server"
                        + " holds)");
    }

    /** Resolves a call's {@code application} argument to its home directory. */
    private Path appHome(JsonNode args) {
        return applications.get(appName(args));
    }

    /** Resolves a call's {@code application} argument to its expression-function set. */
    private ExpressionFunctions functions(JsonNode args) {
        return functions.get(appName(args));
    }

    /** Resolves a call's {@code application} argument to its module classloader. */
    private ClassLoader loader(JsonNode args) {
        return loaders.get(appName(args));
    }

    /** Resolves a call's {@code application} argument to its name, defaulted for a sole app. */
    private String appName(JsonNode args) {
        String name = textOrNull(args, "application");
        if (name == null || name.isBlank()) {
            if (applications.size() == 1) {
                return soleName();
            }
            throw new TqlException(BAD_ARGS, "Missing required argument: application. This server"
                    + " spans " + names() + " - name the one the request concerns.");
        }
        if (!applications.containsKey(name)) {
            throw new TqlException(BAD_ARGS, "Unknown application '" + name + "'. This server"
                    + " holds: " + names());
        }
        return name;
    }

    private String names() {
        return String.join(", ", applications.keySet());
    }

    private String soleName() {
        return applications.keySet().iterator().next();
    }

    // ----- read tools -------------------------------------------------------

    private McpTool manifestSummary() {
        return McpTool.builder("manifest_summary")
                .description("Summarize an application: name, home, reproducibility hash, and"
                        + " every discovered route and job.")
                .inputSchema(schema())
                .handler((args, ctx) -> {
                    Path app = appHome(args);
                    AppManifest manifest = new ManifestLoader().load(app, functions(args));
                    StudioService.Explorer explorer = new StudioService(manifest, true,
                            functions(args)).explorer();
                    return McpToolResult.json(obj(
                            "appName", explorer.appName(),
                            "appHome", app.toString(),
                            "reproducibilityHash", manifest.index().aggregateHash(),
                            "routes", explorer.routes(),
                            "jobs", explorer.jobs()));
                })
                .build();
    }

    private McpTool sourceRead() {
        return McpTool.builder("source_read")
                .description("Read one application source file (YAML, SQL, or template) by its"
                        + " app-relative path.")
                .inputSchema(schema()
                        .required("path", "string", "app-home-relative file path"))
                .handler((args, ctx) -> {
                    AppManifest manifest = new ManifestLoader().load(appHome(args),
                            functions(args));
                    String content = new StudioService(manifest, true, functions(args))
                            .source(requireText(args, "path"));
                    return McpToolResult.text(content);
                })
                .build();
    }

    private McpTool schemaIntrospect() {
        return McpTool.builder("schema_introspect")
                .description("Introspect a database table's columns, primary key, version column,"
                        + " and unique indexes via JDBC metadata.")
                .inputSchema(schema()
                        .required("table", "string", "table name to introspect")
                        .property("jdbcUrl", "string",
                                "JDBC URL (default: the application's main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    Path app = appHome(args);
                    String table = requireText(args, "table");
                    TableSchema schema;
                    try (Connection connection = connect(args, app)) {
                        schema = new TableIntrospector().introspect(connection, table);
                    }
                    return McpToolResult.json(schemaJson(schema));
                })
                .build();
    }

    private McpTool lint() {
        return McpTool.builder("lint")
                .description("Run the application linter (recipes, SQL files, security policies,"
                        + " tenant and optimistic-locking rules, validation, notify, i18n) and"
                        + " report findings.")
                .inputSchema(schema())
                .handler((args, ctx) -> {
                    List<LintFinding> findings = new AppLinter().lint(appHome(args),
                            functions(args));
                    long errors = findings.stream().filter(LintFinding::isError).count();
                    return McpToolResult.json(obj(
                            "errors", errors,
                            "warnings", findings.size() - errors,
                            "findings", findings));
                })
                .build();
    }

    private McpTool test() {
        return McpTool.builder("test")
                .description("Run an application's declarative test suites against the datasource,"
                        + " collect SQL and item coverage, and evaluate the coverage gate.")
                .inputSchema(schema()
                        .property("jdbcUrl", "string",
                                "JDBC URL (default: the application's main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password")
                        .property("realm", "string", "managed realm id (default: local)"))
                .handler(this::runTests)
                .build();
    }

    private McpToolResult runTests(JsonNode args, McpCallContext context) throws Exception {
        Path app = appHome(args);
        AppConfig config = config(app);
        Datasource ds = resolve(args, app, config);
        String realm = textOr(args, "realm", "local");
        Path reportDir = io.tesseraql.yaml.config.WorkHome.resolve(app, config)
                .resolve("mcp/reports");
        Files.createDirectories(reportDir);
        AppTestRunner.RunResult result = new AppTestRunner().run(app,
                dataSource(ds.url(), ds.user(), ds.password(), loader(args)),
                RealmConfig.managed(realm, "main"), reportDir, functions(args));

        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(config, 0, 0);
        CoverageGate.Result gate = CoverageGate.check(result.coverage(), result.kinds(),
                thresholds);

        List<Map<String, Object>> failures = result.report().results().stream()
                .filter(r -> !r.passed())
                .map(r -> obj("name", r.name(), "message", r.message()))
                .toList();
        Map<String, Object> kinds = new LinkedHashMap<>();
        for (ItemCoverage kind : result.kinds()) {
            kinds.put(kind.kind(), obj("ratio", kind.ratio(),
                    "covered", kind.covered().size(), "declared", kind.declared().size(),
                    "uncovered", kind.uncovered().stream().sorted().toList()));
        }
        Map<String, Object> sql = new LinkedHashMap<>();
        for (Map.Entry<String, SqlCoverageReport> entry : result.coverage().reports().entrySet()) {
            SqlCoverageReport report = entry.getValue();
            sql.put(entry.getKey(), obj("lineRatio", report.lineRatio(),
                    "branchRatio", report.branchRatio()));
        }
        return McpToolResult.json(obj(
                "passed", result.report().allPassed() && gate.passed(),
                "tests", obj("total", result.report().results().size(),
                        "passed", result.report().passed(), "failed", result.report().failed(),
                        "failures", failures),
                "coverage", obj("gatePassed", gate.passed(), "violations", gate.violations(),
                        "kinds", kinds, "sql", sql)));
    }

    private McpTool opsStatus() {
        return McpTool.builder("ops_status")
                .description("Read operational status from the datasource: outbox event counts and"
                        + " recent events, and recent batch job executions.")
                .inputSchema(schema()
                        .property("limit", "integer", "max recent rows (default 20)")
                        .property("jdbcUrl", "string",
                                "JDBC URL (default: the application's main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    Path app = appHome(args);
                    Datasource ds = resolve(args, app, config(app));
                    DataSource dataSource = dataSource(ds.url(), ds.user(), ds.password(),
                            loader(args));
                    int limit = args.path("limit").isNumber() ? args.get("limit").asInt() : 20;
                    try {
                        JdbcOutboxStore outbox = new JdbcOutboxStore(dataSource);
                        JobRepository jobs = new JobRepository(dataSource);
                        return McpToolResult.json(obj(
                                "outbox", obj("byStatus", outbox.countByStatus(),
                                        "recent", outbox.recent(limit).stream()
                                                .map(McpDevTools::outboxJson).toList()),
                                "jobs", jobs.listExecutions(limit).stream()
                                        .map(McpDevTools::jobJson).toList()));
                    } catch (TqlException ex) {
                        // isError so an agent can tell a broken ops schema from "no events yet",
                        // instead of reading a healthy-looking success envelope with only a note.
                        return McpToolResult.error(
                                "operations schema not present or unreadable: " + ex.getMessage());
                    }
                })
                .build();
    }

    // ----- write tools (gated) ----------------------------------------------

    private McpTool scaffoldCrud() {
        return McpTool.builder("scaffold_crud")
                .description(
                        "Scaffold list/detail/edit routes, 2-way SQL, htmx pages, and tests for"
                                + " a table. Idempotent; hand-edited files are skipped unless force is set.")
                .inputSchema(schema()
                        .required("table", "string", "table to scaffold")
                        .property("force", "boolean", "overwrite edited or user-owned files")
                        .property("jdbcUrl", "string",
                                "JDBC URL (default: the application's main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    Path app = appHome(args);
                    AppConfig config = config(app);
                    String table = requireText(args, "table");
                    boolean force = args.path("force").asBoolean(false);
                    TableSchema schema;
                    try (Connection connection = connect(args, app)) {
                        schema = new TableIntrospector().introspect(connection, table);
                    }
                    List<ScaffoldedFile> files = new CrudScaffolder(SecurityDefaults.from(config),
                            ResponseHeaderDefaults.from(config),
                            io.tesseraql.yaml.catalog.Catalogs.load(app).all().values()
                                    .stream().map(io.tesseraql.yaml.model.CatalogSpec::table)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(java.util.stream.Collectors.toUnmodifiableSet()))
                            .scaffold(schema);
                    ScaffoldWriter.Report report = new ScaffoldWriter().apply(app, files,
                            force);
                    Object payload = obj(
                            "written", report.written(),
                            "unchanged", report.unchanged(),
                            "skipped", report.skipped(),
                            "blocked", report.blocked());
                    // Blocked (hand-edited) files mean the scaffold did not fully apply; the CLI
                    // exits 1 for the same condition, so the agent must see isError too — while
                    // still receiving the blocked list.
                    return report.blocked()
                            ? McpToolResult.jsonError(payload)
                            : McpToolResult.json(payload);
                })
                .build();
    }

    private McpTool draftSave() {
        return McpTool.builder("draft_save")
                .description("Save a draft edit of a file under work/studio/drafts without touching"
                        + " the source of truth.")
                .inputSchema(schema()
                        .required("path", "string", "app-home-relative file path")
                        .required("content", "string", "new file content"))
                .handler((args, ctx) -> {
                    StudioService studio = studio(appHome(args), functions(args));
                    studio.saveDraft(requireText(args, "path"), requireText(args, "content"));
                    return McpToolResult.json(obj("saved", requireText(args, "path")));
                })
                .build();
    }

    private McpTool draftPreview() {
        return McpTool.builder("draft_preview")
                .description("Validate a draft (or supplied content) by compiling it - parse route"
                        + " YAML, render SQL, process templates - without applying it.")
                .inputSchema(schema()
                        .required("path", "string", "app-home-relative file path")
                        .property("content", "string", "content to validate (default: saved draft"
                                + " or current source)"))
                .handler((args, ctx) -> {
                    StudioService.PreviewResult preview = studio(appHome(args), functions(args))
                            .preview(requireText(args, "path"), textOrNull(args, "content"));
                    return McpToolResult.json(obj(
                            "valid", preview.valid(), "kind", preview.kind(),
                            "result", preview.result(), "error", preview.error()));
                })
                .build();
    }

    private McpTool draftApply() {
        return McpTool.builder("draft_apply")
                .description("Promote a saved draft to the source of truth after it compiles. Fails"
                        + " if the draft does not compile.")
                .inputSchema(schema()
                        .required("path", "string", "app-home-relative file path"))
                .handler((args, ctx) -> {
                    String path = requireText(args, "path");
                    studio(appHome(args), functions(args)).applyDraft(path);
                    return McpToolResult.json(obj("applied", path));
                })
                .build();
    }

    // ----- prompts (the Studio copilot loop) --------------------------------

    /**
     * The Studio-copilot prompt (Studio backlog G): a guided "describe -&gt; draft -&gt; preview
     * -&gt; apply" workflow. It is the missing <em>describe</em> entry point - the connecting agent's
     * model does the natural-language reasoning, and this prompt steers it through the existing dev
     * tools so a plain-language request becomes a verified route or job. No model runs inside
     * TesseraQL; the prompt only returns guidance text.
     */
    private McpPrompt studioCopilot() {
        McpPrompt.Builder builder = McpPrompt.builder("studio_copilot")
                .title("Studio copilot: describe -> draft -> preview -> apply")
                .description(
                        "Turn a plain-language request into a verified TesseraQL route or job, "
                                + "using the dev tools (scaffold/draft/preview/lint/test/apply).")
                .argument("task", "What to build, in plain language (e.g. 'a JSON endpoint that "
                        + "lists active users').", true)
                .argument("table",
                        "The backing table, when the request is table-backed (optional).",
                        false);
        if (applications.size() > 1) {
            builder.argument("application", "Which application to build it in - one of: "
                    + names() + ".", true);
        }
        return builder
                .handler((args, ctx) -> McpPromptResult.user(
                        "Studio copilot: describe -> draft -> preview -> apply.",
                        copilotGuidance(args.get("task"), args.get("table"),
                                args.get("application"))))
                .build();
    }

    /** Renders the copilot guidance for a request, naming the exact tools to drive each step. */
    private String copilotGuidance(String task, String table, String application) {
        StringBuilder text = new StringBuilder();
        text.append("Help the developer build a TesseraQL route or job for this request:\n\n  ")
                .append(task == null || task.isBlank()
                        ? "(no task given - ask the developer)"
                        : task)
                .append("\n\n");
        if (table != null && !table.isBlank()) {
            text.append("Backing table: ").append(table).append("\n\n");
        }
        if (applications.size() > 1) {
            text.append("Build it in the application '")
                    .append(application == null || application.isBlank()
                            ? "(none named - ask the developer; this server spans " + names() + ")"
                            : application)
                    .append("', and pass application: '")
                    .append(application == null || application.isBlank() ? "<name>" : application)
                    .append("' on every tool call - this server spans several applications.\n\n");
        }
        text.append(
                """
                        TesseraQL apps are file-based: routes are Simple YAML at \
                        web/<path>/<method>.yml with colocated .sql/templates; MCP tools (kind: tool) \
                        live under mcp/. Drive this loop with the dev tools - do not edit files directly, \
                        and keep every path inside the app home (no ../):

                        1. Orient. Call manifest_summary to see the app's routes/jobs and conventions, then \
                        source_read one similar existing route to copy its shape (recipe, security, \
                        response).
                        2. Draft. For a table-backed CRUD slice, call scaffold_crud with the table - it \
                        writes a complete, idempotent slice; prefer it over hand-writing. Otherwise write \
                        the route YAML (and its .sql) with draft_save, matching the conventions you saw.
                        3. Preview. Call draft_preview on each drafted path and fix the YAML/SQL until it \
                        reports valid: true - a draft only applies if it compiles.
                        4. Verify. Run lint, then test; fix findings and failing cases (re-draft and \
                        re-preview as needed) until both are clean.
                        5. Apply. Only once preview is valid and lint/test pass, call draft_apply for each \
                        path. Tell the developer that a brand-new route needs a server restart to be served \
                        (the hot reloader only swaps existing routes).

                        Finish by reporting what you built, which tools you ran, and anything the developer \
                        must still do (restart, add a policy, write a migration).""");
        return text.toString();
    }

    // ----- helpers ----------------------------------------------------------

    private AppConfig config(Path app) {
        return new ManifestLoader().load(app).config();
    }

    private StudioService studio(Path app, ExpressionFunctions functions) {
        return new StudioService(new ManifestLoader().load(app, functions), readOnly, functions);
    }

    private Connection connect(JsonNode args, Path app) throws java.sql.SQLException {
        Datasource ds = resolve(args, app, config(app));
        return dataSource(ds.url(), ds.user(), ds.password(), loader(args)).getConnection();
    }

    /**
     * A datasource over the application's module-defined driver when one accepts the URL
     * (docs/module-scope.md), else over {@code DriverManager}.
     */
    private static DataSource dataSource(String url, String user, String password,
            ClassLoader loader) {
        java.sql.Driver driver = DataSources.moduleDriver(url, loader);
        if (driver == null) {
            return new DriverManagerDataSource(url, user, password);
        }
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        return new DriverBackedDataSource(driver, url, properties);
    }

    /**
     * Resolves a datasource with the CLI subcommands' precedence: explicit arguments, then the
     * application's main datasource when it resolves and answers, then a running
     * {@code dev --embedded-db} (its {@code work/embedded-db.jdbc} marker) — so the agent's
     * database tools work against the embedded database another terminal is serving.
     */
    private Datasource resolve(JsonNode args, Path app, AppConfig config) {
        String url = textOrNull(args, "jdbcUrl");
        String user = textOrNull(args, "username");
        String password = textOrNull(args, "password");
        if (url != null) {
            return new Datasource(url, user, password);
        }
        String configUrl;
        String configFailure = null;
        try {
            configUrl = config.getString("tesseraql.datasources.main.jdbcUrl").orElse(null);
            if (user == null) {
                user = config.getString("tesseraql.datasources.main.username").orElse(null);
            }
            if (password == null) {
                password = config.getString("tesseraql.datasources.main.password").orElse(null);
            }
        } catch (RuntimeException ex) {
            // Unresolvable placeholders (e.g. ${db.main.url} with no input declared) — exactly
            // the situation the running embedded database can answer for. The reason is kept:
            // "the config could not be read" and "the config declares nothing" are different
            // problems, and the agent was being told the second one either way.
            configUrl = null;
            configFailure = ex.getMessage();
        }
        Optional<String> marker = EmbeddedDbMarker.pick(app, configUrl, user, password,
                EmbeddedDbMarker::reachable);
        if (marker.isPresent()) {
            return new Datasource(marker.get(), null, null);
        }
        if (configUrl == null) {
            throw new TqlException(NO_DATASOURCE, configFailure == null
                    ? "No jdbcUrl argument, the app config declares no"
                            + " tesseraql.datasources.main.jdbcUrl, and no running"
                            + " dev --embedded-db was found"
                    : "No jdbcUrl argument, the app config's datasource could not be read ("
                            + configFailure + "), and no running dev --embedded-db was found");
        }
        return new Datasource(configUrl, user, password);
    }

    private static Map<String, Object> schemaJson(TableSchema schema) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (TableSchema.Column column : schema.columns()) {
            columns.add(obj(
                    "name", column.name(),
                    "inputType", column.inputType(),
                    "typeName", column.typeName(),
                    "nullable", column.nullable(),
                    "primaryKey", schema.isPrimaryKey(column),
                    "autoGenerated", column.autoGenerated(),
                    "required", column.isRequired()));
        }
        return obj(
                "table", schema.name(),
                "primaryKey", schema.primaryKey(),
                "versionColumn", schema.versionColumn().map(TableSchema.Column::name).orElse(null),
                "uniqueIndexes", schema.uniqueIndexes(),
                "columns", columns);
    }

    private static Map<String, Object> outboxJson(OutboxEvent event) {
        return obj("id", event.id(), "eventType", event.eventType(), "status", event.status(),
                "attempts", event.attempts(), "appName", event.appName(),
                "createdAt", instant(event.createdAt()), "lastError", event.lastError());
    }

    private static Map<String, Object> jobJson(JobExecution execution) {
        return obj("id", execution.id(), "jobId", execution.jobId(),
                "status", execution.status() == null ? null : execution.status().name(),
                "trigger", execution.triggerType(), "startTime", instant(execution.startTime()),
                "endTime", instant(execution.endTime()), "durationMs", execution.durationMs(),
                "exitMessage", execution.exitMessage());
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    private String requireText(JsonNode args, String name) {
        String value = textOrNull(args, name);
        if (value == null || value.isBlank()) {
            throw new TqlException(BAD_ARGS, "Missing required argument: " + name);
        }
        return value;
    }

    private static String textOrNull(JsonNode args, String name) {
        JsonNode node = args.get(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String textOr(JsonNode args, String name, String fallback) {
        String value = textOrNull(args, name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Builds an insertion-ordered map from alternating key/value arguments. */
    private static Map<String, Object> obj(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private record Datasource(String url, String user, String password) {
    }
}
