package io.tesseraql.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
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
import io.tesseraql.studio.StudioService;
import io.tesseraql.yaml.config.AppConfig;
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
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exposes TesseraQL's developer surfaces - manifest, sources, schema introspection, lint, tests,
 * coverage, ops status, scaffolding, and Studio drafts - as MCP tools, so an agent connected only
 * over MCP can scaffold a table-backed route and iterate until lint, tests, and coverage pass
 * without touching the filesystem directly (roadmap Phase 24).
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

    private static final String INSTRUCTIONS = """
            TesseraQL developer tools. Typical loop: scaffold_crud a table, then lint and test \
            until both pass. Edit files through draft_save -> draft_preview -> draft_apply (a draft \
            only applies if it compiles). All paths are app-home-relative and confined to the app. \
            schema_introspect, test, and ops_status use the app's configured datasource unless you \
            pass jdbcUrl/username/password. To build from a plain-language request, start with the \
            studio_copilot prompt, which walks the describe -> draft -> preview -> apply loop.""";

    private final Path appHome;
    private final boolean readOnly;

    public McpDevTools(Path appHome, boolean readOnly) {
        this.appHome = appHome.toAbsolutePath().normalize();
        this.readOnly = readOnly;
    }

    /**
     * Builds the MCP server with the read tools, plus the gated write tools and the Studio-copilot
     * prompt when writable (the prompt drives the write loop, so it is offered only in write mode).
     */
    public McpServer toServer() {
        McpServer.Builder builder = McpServer.builder("tesseraql-dev", VERSION)
                .instructions(INSTRUCTIONS)
                .tools(readTools());
        if (!readOnly) {
            builder.tools(writeTools());
            builder.prompts(prompts());
        }
        return builder.build();
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

    // ----- read tools -------------------------------------------------------

    private McpTool manifestSummary() {
        return McpTool.builder("manifest_summary")
                .description("Summarize the app: name, home, reproducibility hash, and every"
                        + " discovered route and job.")
                .handler((args, ctx) -> {
                    AppManifest manifest = new ManifestLoader().load(appHome);
                    StudioService.Explorer explorer = new StudioService(manifest, true).explorer();
                    return McpToolResult.json(obj(
                            "appName", explorer.appName(),
                            "appHome", appHome.toString(),
                            "reproducibilityHash", manifest.index().aggregateHash(),
                            "routes", explorer.routes(),
                            "jobs", explorer.jobs()));
                })
                .build();
    }

    private McpTool sourceRead() {
        return McpTool.builder("source_read")
                .description("Read one app source file (YAML, SQL, or template) by its app-relative"
                        + " path.")
                .inputSchema(McpSchema.object()
                        .required("path", "string", "app-home-relative file path"))
                .handler((args, ctx) -> {
                    AppManifest manifest = new ManifestLoader().load(appHome);
                    String content = new StudioService(manifest, true)
                            .source(requireText(args, "path"));
                    return McpToolResult.text(content);
                })
                .build();
    }

    private McpTool schemaIntrospect() {
        return McpTool.builder("schema_introspect")
                .description("Introspect a database table's columns, primary key, version column,"
                        + " and unique indexes via JDBC metadata.")
                .inputSchema(McpSchema.object()
                        .required("table", "string", "table name to introspect")
                        .property("jdbcUrl", "string", "JDBC URL (default: app main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    String table = requireText(args, "table");
                    TableSchema schema;
                    try (Connection connection = connect(args, config())) {
                        schema = new TableIntrospector().introspect(connection, table);
                    }
                    return McpToolResult.json(schemaJson(schema));
                })
                .build();
    }

    private McpTool lint() {
        return McpTool.builder("lint")
                .description("Run the app linter (recipes, SQL files, security policies, tenant and"
                        + " optimistic-locking rules, validation, notify, i18n) and report findings.")
                .handler((args, ctx) -> {
                    List<LintFinding> findings = new AppLinter().lint(appHome);
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
                .description("Run the app's declarative test suites against the datasource, collect"
                        + " SQL and item coverage, and evaluate the coverage gate.")
                .inputSchema(McpSchema.object()
                        .property("jdbcUrl", "string", "JDBC URL (default: app main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password")
                        .property("realm", "string", "managed realm id (default: local)"))
                .handler(this::runTests)
                .build();
    }

    private McpToolResult runTests(JsonNode args, McpCallContext context) throws Exception {
        Datasource ds = resolve(args, config());
        String realm = textOr(args, "realm", "local");
        Path reportDir = appHome.resolve("work/mcp/reports");
        Files.createDirectories(reportDir);
        AppTestRunner.RunResult result = new AppTestRunner().run(appHome,
                new DriverManagerDataSource(ds.url(), ds.user(), ds.password()),
                RealmConfig.managed(realm, "main"), reportDir);

        CoverageThresholds thresholds = CoverageThresholdResolver.resolve(config(), 0, 0);
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
                .inputSchema(McpSchema.object()
                        .property("limit", "integer", "max recent rows (default 20)")
                        .property("jdbcUrl", "string", "JDBC URL (default: app main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    Datasource ds = resolve(args, config());
                    DriverManagerDataSource dataSource = new DriverManagerDataSource(ds.url(),
                            ds.user(), ds.password());
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
                        return McpToolResult.json(obj("note",
                                "operations schema not present or unreadable: " + ex.getMessage()));
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
                .inputSchema(McpSchema.object()
                        .required("table", "string", "table to scaffold")
                        .property("force", "boolean", "overwrite edited or user-owned files")
                        .property("jdbcUrl", "string", "JDBC URL (default: app main datasource)")
                        .property("username", "string", "database user")
                        .property("password", "string", "database password"))
                .handler((args, ctx) -> {
                    String table = requireText(args, "table");
                    boolean force = args.path("force").asBoolean(false);
                    TableSchema schema;
                    try (Connection connection = connect(args, config())) {
                        schema = new TableIntrospector().introspect(connection, table);
                    }
                    List<ScaffoldedFile> files = new CrudScaffolder().scaffold(schema);
                    ScaffoldWriter.Report report = new ScaffoldWriter().apply(appHome, files,
                            force);
                    return McpToolResult.json(obj(
                            "written", report.written(),
                            "unchanged", report.unchanged(),
                            "skipped", report.skipped(),
                            "blocked", report.blocked()));
                })
                .build();
    }

    private McpTool draftSave() {
        return McpTool.builder("draft_save")
                .description("Save a draft edit of a file under work/studio/drafts without touching"
                        + " the source of truth.")
                .inputSchema(McpSchema.object()
                        .required("path", "string", "app-home-relative file path")
                        .required("content", "string", "new file content"))
                .handler((args, ctx) -> {
                    StudioService studio = studio();
                    studio.saveDraft(requireText(args, "path"), requireText(args, "content"));
                    return McpToolResult.json(obj("saved", requireText(args, "path")));
                })
                .build();
    }

    private McpTool draftPreview() {
        return McpTool.builder("draft_preview")
                .description("Validate a draft (or supplied content) by compiling it - parse route"
                        + " YAML, render SQL, process templates - without applying it.")
                .inputSchema(McpSchema.object()
                        .required("path", "string", "app-home-relative file path")
                        .property("content", "string", "content to validate (default: saved draft"
                                + " or current source)"))
                .handler((args, ctx) -> {
                    StudioService.PreviewResult preview = studio()
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
                .inputSchema(McpSchema.object()
                        .required("path", "string", "app-home-relative file path"))
                .handler((args, ctx) -> {
                    String path = requireText(args, "path");
                    studio().applyDraft(path);
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
        return McpPrompt.builder("studio_copilot")
                .title("Studio copilot: describe -> draft -> preview -> apply")
                .description(
                        "Turn a plain-language request into a verified TesseraQL route or job, "
                                + "using the dev tools (scaffold/draft/preview/lint/test/apply).")
                .argument("task", "What to build, in plain language (e.g. 'a JSON endpoint that "
                        + "lists active users').", true)
                .argument("table",
                        "The backing table, when the request is table-backed (optional).",
                        false)
                .handler(args -> McpPromptResult.user(
                        "Studio copilot: describe -> draft -> preview -> apply.",
                        copilotGuidance(args.get("task"), args.get("table"))))
                .build();
    }

    /** Renders the copilot guidance for a request, naming the exact tools to drive each step. */
    private static String copilotGuidance(String task, String table) {
        StringBuilder text = new StringBuilder();
        text.append("Help the developer build a TesseraQL route or job for this request:\n\n  ")
                .append(task == null || task.isBlank()
                        ? "(no task given - ask the developer)"
                        : task)
                .append("\n\n");
        if (table != null && !table.isBlank()) {
            text.append("Backing table: ").append(table).append("\n\n");
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

    private AppConfig config() {
        return new ManifestLoader().load(appHome).config();
    }

    private StudioService studio() {
        return new StudioService(new ManifestLoader().load(appHome), readOnly);
    }

    private Connection connect(JsonNode args, AppConfig config) throws java.sql.SQLException {
        Datasource ds = resolve(args, config);
        return DriverManager.getConnection(ds.url(), ds.user(), ds.password());
    }

    /** Resolves a datasource from explicit arguments, falling back to the app's main datasource. */
    private Datasource resolve(JsonNode args, AppConfig config) {
        String url = textOrNull(args, "jdbcUrl");
        String user = textOrNull(args, "username");
        String password = textOrNull(args, "password");
        if (url == null) {
            url = config.getString("tesseraql.datasources.main.jdbcUrl").orElseThrow(
                    () -> new TqlException(NO_DATASOURCE, "No jdbcUrl argument and the app config"
                            + " declares no tesseraql.datasources.main.jdbcUrl"));
            if (user == null) {
                user = config.getString("tesseraql.datasources.main.username").orElse(null);
            }
            if (password == null) {
                password = config.getString("tesseraql.datasources.main.password").orElse(null);
            }
        }
        return new Datasource(url, user, password);
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
