package io.tesseraql.studio.runtime;

import io.tesseraql.core.dialect.JdbcValues;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.studio.StudioService;
import io.tesseraql.test.CrossReferenceIndex;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestRunner;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuiteLoader;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.JobFile;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import io.tesseraql.yaml.model.Binding;
import io.tesseraql.yaml.model.PipelineStep;
import io.tesseraql.yaml.model.RouteDefinition;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Runs a route's or job's declarative test cases from Studio against the dev datasource and returns
 * an inline result model (Studio backlog A2 — "run tests now" in the editor).
 *
 * <p>Gated and sandboxed (decided with the maintainer): enabled only when Studio is writable and
 * {@code tesseraql.studio.testRunner.enabled} is set; every case runs through a
 * {@link SandboxDataSource} — an auto-rollback transaction (commits suppressed, rolled back on
 * close) with a statement timeout and a row cap — so a case can neither run away nor persist a
 * write. Every declarative case kind runs: {@code sql} reads and writes (an {@code INSERT …
 * RETURNING} executes and its rows are checked, then rolled back), {@code validate} rules (their
 * SQL runs against the sandbox), {@code contract} cases (run through a sandboxed identity service
 * over the same datasources), and the pure (no DB) {@code notify} and {@code httpCall} evaluations
 * (the latter plans a job's outbound step without a network call).
 */
final class StudioTestService {

    private final Function<String, DataSource> datasources;
    private final Path appHome;
    private final RealmConfig realm;
    private final String dialect;
    private final boolean enabled;
    private final int queryTimeoutSeconds;
    private final int maxRows;
    private final io.tesseraql.core.expr.ExpressionFunctions functions;

    StudioTestService(Function<String, DataSource> datasources, Path appHome, RealmConfig realm,
            String dialect, boolean enabled, int queryTimeoutSeconds, int maxRows,
            io.tesseraql.core.expr.ExpressionFunctions functions) {
        this.datasources = datasources;
        this.functions = functions;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.realm = realm;
        this.dialect = dialect;
        this.enabled = enabled;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxRows = maxRows;
    }

    boolean isEnabled() {
        return enabled;
    }

    /** A sandboxed view of a named datasource: read/write but auto-rollback, capped, timed out. */
    private SandboxDataSource sandbox(String name) {
        return new SandboxDataSource(datasources.apply(name), queryTimeoutSeconds, maxRows);
    }

    /**
     * Runs the declarative test cases covering the route or job at {@code relativePath} and returns a
     * template-ready result model. A disabled runner, an unknown path, or one with no covering cases
     * each comes back as a {@code ran: false} model carrying an explanatory note.
     */
    Map<String, Object> runForPath(String relativePath) {
        if (!enabled) {
            return notRun(relativePath, "The Studio test runner is disabled "
                    + "(set tesseraql.studio.testRunner.enabled and make Studio writable).");
        }
        AppManifest manifest = new ManifestLoader().load(appHome);
        List<TestSuite> suites = loadSuites();
        List<TestCase> covering;
        RouteFile route = manifest.routes().stream()
                .filter(file -> relativePath.equals(relative(file.source())))
                .findFirst()
                .orElse(null);
        if (route != null) {
            covering = CrossReferenceIndex.of(manifest, suites).casesFor(route);
        } else {
            JobFile job = manifest.jobs().stream()
                    .filter(file -> relativePath.equals(relative(file.source())))
                    .findFirst()
                    .orElse(null);
            if (job == null) {
                return notRun(relativePath,
                        "No route or job is declared at " + relativePath + ".");
            }
            covering = casesForJob(job, suites);
        }
        List<TestCase> runnable = covering.stream()
                .filter(StudioTestService::isRunnable)
                .toList();
        if (runnable.isEmpty()) {
            return notRun(relativePath, "No runnable test cases cover this file.");
        }
        // A sandboxed identity service (over the same sandboxed datasources) runs contract cases,
        // so their identity SELECTs are capped/timed-out like every other case.
        IdentityService identity = new IdentityService(this::sandbox, dialect);
        // The application's registry: under dev and host nothing installs a process default,
        // so the four-argument runner made every module function unknown here while the same
        // case passed under tesseraql test (docs/audit-low-leads.md G20).
        TestReport report = new TestRunner(sandbox("main"), appHome, identity, realm, null,
                functions).run(new TestSuite(runnable));
        return result(relativePath, report);
    }

    /**
     * The test cases covering a job: {@code notify}/{@code httpCall} cases targeting it by id, and
     * {@code sql}/{@code contract} cases exercising one of its (main or step) SQL files or Identity
     * SQL Contracts. Mirrors {@link CrossReferenceIndex#casesFor} for routes, which the index does
     * not provide for jobs.
     */
    private List<TestCase> casesForJob(JobFile job, List<TestSuite> suites) {
        String jobId = job.definition().id();
        Path jobDir = job.source().getParent();
        Set<Path> sqlFiles = new LinkedHashSet<>();
        Set<String> contracts = new LinkedHashSet<>();
        for (PipelineStep step : job.definition().effectiveSteps()) {
            bindings(step.sql(), jobDir, sqlFiles, contracts);
        }
        List<TestCase> cases = new ArrayList<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (linksJob(test, jobId, sqlFiles, contracts)) {
                    cases.add(test);
                }
            }
        }
        return cases;
    }

    private void bindings(Binding binding, Path jobDir, Set<Path> sqlFiles,
            Set<String> contracts) {
        if (binding == null) {
            return;
        }
        if (binding.file() != null) {
            sqlFiles.add(jobDir.resolve(binding.file()).normalize());
        }
        if (binding.contract() != null) {
            contracts.add(CrossReferenceIndex.stripIdentityPrefix(binding.contract()));
        }
    }

    private boolean linksJob(TestCase test, String jobId, Set<Path> sqlFiles,
            Set<String> contracts) {
        if (test.notifications() != null && jobId.equals(test.notifications().job())) {
            return true;
        }
        if (test.httpCall() != null && jobId.equals(test.httpCall().job())) {
            return true;
        }
        if (test.contract() != null && !test.contract().isBlank()
                && contracts.contains(CrossReferenceIndex.stripIdentityPrefix(test.contract()))) {
            return true;
        }
        return test.sql() != null && test.sql().file() != null
                && sqlFiles.contains(appHome.resolve(test.sql().file()).normalize());
    }

    /**
     * A declarative case the sandbox can run: a {@code sql} query or write (its SQL runs against the
     * sandbox — a write executes and is rolled back), a {@code validate} rule, a {@code contract}
     * (run through the sandboxed identity service), or a {@code notify}/{@code httpCall} evaluation
     * (the last two are pure, no DB). {@code messages} cases carry no DB/route/job binding.
     */
    private static boolean isRunnable(TestCase test) {
        return (test.sql() != null && test.sql().file() != null)
                || (test.contract() != null && !test.contract().isBlank())
                || test.validate() != null
                || test.notifications() != null
                || test.httpCall() != null;
    }

    private List<TestSuite> loadSuites() {
        Path testsDir = appHome.resolve("tests");
        if (!Files.isDirectory(testsDir)) {
            return List.of();
        }
        TestSuiteLoader loader = new TestSuiteLoader();
        try (Stream<Path> files = Files.walk(testsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .map(loader::load)
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private Map<String, Object> result(String relativePath, TestReport report) {
        List<Map<String, Object>> cases = new ArrayList<>();
        int passed = 0;
        for (TestReport.TestResult testResult : report.results()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", testResult.name());
            row.put("passed", testResult.passed());
            row.put("message", testResult.message());
            cases.add(row);
            if (testResult.passed()) {
                passed++;
            }
        }
        Map<String, Object> model = baseModel(relativePath);
        model.put("ran", true);
        model.put("total", cases.size());
        model.put("passed", passed);
        model.put("failed", cases.size() - passed);
        model.put("allPassed", passed == cases.size());
        model.put("cases", cases);
        return model;
    }

    private Map<String, Object> notRun(String relativePath, String note) {
        Map<String, Object> model = baseModel(relativePath);
        model.put("ran", false);
        model.put("note", note);
        model.put("cases", List.of());
        return model;
    }

    private Map<String, Object> baseModel(String relativePath) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("path", relativePath);
        model.put("enabled", enabled);
        return model;
    }

    private String relative(Path source) {
        return appHome.relativize(source).toString().replace('\\', '/');
    }

    /**
     * Executes a route's read sources against the sandbox and returns their results keyed by
     * source name for the rendered preview (Studio backlog A1 "real bound params";
     * multi-binding): every SQL {@code query} source under its own name, {@code main} included —
     * matching how the runtime keys each result (docs/unified-sources.md decision 10; the retired
     * {@code sql} key is published no more, and {@code main} runs once). A
     * {@link StudioService.RowSource}. Each query's binds resolve from the render context, which
     * accretes earlier results in authored order (so a later query may read an earlier one); the
     * same {@link SandboxDataSource} guards apply (timeout, row cap, rollback). Returns null —
     * keeping the hand-authored sample — when no binding is a runnable read query (a
     * service/contract/sequence binding, a non-{@code query} mode, or no SQL file). Command
     * {@code steps} (writes) are not previewed live.
     *
     * <p>The rows then pass the row stages the served route runs after its reads
     * (docs/audit-low-leads.md TS-04): each source's {@code result:} declaration right after
     * its read, then every {@code enrich:} in authored order — a {@code sql:} reference against
     * the sandbox, a {@code source:} reference against the results already read. An
     * {@code http:} reference is not called, as an {@code http:} source is not previewed: the
     * sandbox makes no outbound call.
     */
    Map<String, Object> liveRows(RouteDefinition route, Path routeDir,
            Map<String, Object> context) {
        Map<String, Object> results = new LinkedHashMap<>();
        // A working context the named queries resolve their binds against, accreting earlier results
        // in authored order — mirroring how the runtime publishes each result under its own key.
        Map<String, Object> working = new LinkedHashMap<>(context);
        for (Map.Entry<String, Binding> source : route.sources().entrySet()) {
            runInto(results, working, route.id(), source.getKey(), source.getValue(), routeDir);
        }
        if (results.isEmpty()) {
            return null;
        }
        enrich(route, routeDir, results, working);
        return results;
    }

    /**
     * The enrichment stage over the results this preview read, in authored order, through the
     * one keyed-reference algorithm the served route runs (docs/lookups.md, decision 3). A
     * source the preview did not read is not enriched: its rows are the sample's.
     */
    private void enrich(RouteDefinition route, Path routeDir, Map<String, Object> results,
            Map<String, Object> working) {
        route.sources().forEach((into, binding) -> binding.enrich().forEach((name, spec) -> {
            if (spec.http() != null || !(results.get(into) instanceof Map<?, ?> target)
                    || !(target.get("rows") instanceof List<?> rows)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> enriched = enrich(reference(route, routeDir, name, spec),
                    working, (List<Map<String, Object>>) rows);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = new LinkedHashMap<>((Map<String, Object>) target);
            result.put("rows", enriched);
            results.put(into, result);
            working.put(into, result);
        }));
    }

    private List<Map<String, Object>> enrich(io.tesseraql.yaml.enrich.KeyedReference reference,
            Map<String, Object> working, List<Map<String, Object>> rows) {
        try {
            return reference.enrich(sandboxEnvironment(), working, rows);
        } catch (SQLException ex) {
            throw new IllegalStateException("Live enrichment failed: " + ex.getMessage(), ex);
        }
    }

    /** One {@code enrich:} entry as the served route would run it, its SQL read now. */
    private io.tesseraql.yaml.enrich.KeyedReference reference(RouteDefinition route,
            Path routeDir, String name, io.tesseraql.yaml.model.EnrichSpec spec) {
        List<io.tesseraql.core.sql.SqlNode> nodes = List.of();
        String sourcePath = null;
        if (spec.sql() != null) {
            Path file = io.tesseraql.core.dialect.DialectSqlResolver.resolve(
                    io.tesseraql.core.files.ConfinedPath.under(appHome)
                            .confine(routeDir.resolve(spec.sql().file()))
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "SQL file escapes app home: " + spec.sql().file())),
                    dialect);
            nodes = Sql2WayParser.parse(read(file), functions);
            sourcePath = file.toString();
        }
        return new io.tesseraql.yaml.enrich.KeyedReference(name, spec, nodes, sourcePath,
                route.effectiveDatasource(), dialect,
                new io.tesseraql.yaml.enrich.KeyedReference.Bounds(queryTimeoutSeconds, maxRows),
                io.tesseraql.yaml.http.HttpRows::of);
    }

    /**
     * What a reference needs, answered by the sandbox: its connections (auto-rollback, capped,
     * timed out — the preview's contract), no data scope (the main query renders under none
     * either), no outbound gateway (an {@code http:} reference is never built here), and no
     * meter for a degraded enrichment — the reference's own log line is the signal.
     */
    private io.tesseraql.yaml.enrich.KeyedReference.Environment sandboxEnvironment() {
        return new io.tesseraql.yaml.enrich.KeyedReference.Environment() {
            @Override
            public Connection connection(String datasource) throws SQLException {
                return sandbox("main").getConnection();
            }

            @Override
            public io.tesseraql.core.sql.ScopeResolver scopeResolver() {
                return io.tesseraql.core.sql.ScopeResolver.UNSUPPORTED;
            }

            @Override
            public io.tesseraql.yaml.http.OutboundGateway gateway() {
                return null;
            }

            @Override
            public void degraded(String enrichment) {
                // The preview has no meter; KeyedReference has already logged the degrade.
            }
        };
    }

    /**
     * Runs the route's main read binding once in the sandbox and returns its row count (the test
     * recorder's expectation capture, Track J3) — null when the binding is not a runnable read or
     * the sandboxed run fails (the case is then recorded without an expectation).
     */
    Integer sandboxRowCount(io.tesseraql.yaml.model.RouteDefinition route,
            java.nio.file.Path routeDir, Map<String, Object> context) {
        try {
            Map<String, Object> result = runQuery(route.main(), routeDir,
                    new LinkedHashMap<>(context));
            return result == null ? null : (Integer) result.get("rowCount");
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Runs one read binding and, on success, records its {@code {rows,rowCount}} under
     * {@code key} — with the binding's {@code result:} declaration applied to the rows first,
     * the one application the served route's source processor runs
     * (docs/temporal-semantics.md T3).
     */
    private void runInto(Map<String, Object> results, Map<String, Object> working,
            String routeId, String key, Binding binding, Path routeDir) {
        Map<String, Object> result = runQuery(binding, routeDir, working);
        if (result == null) {
            return;
        }
        if (!binding.result().isEmpty()) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
            result.put("rows", io.tesseraql.compiler.binding.ResultDeclarationProcessor
                    .apply(routeId, key, binding.result(), rows));
        }
        results.put(key, result);
        working.put(key, result);
    }

    /**
     * Dry-runs a migration's DDL against the sandbox (auto-rollback) so it never persists, for the
     * editor's migration dry-run (Studio backlog: migration authoring). Postgres only: its DDL is
     * transactional and rolls back cleanly, whereas MySQL/Oracle/SQL Server auto-commit DDL — a
     * dry-run there could leave changes behind, so it is declined. The DDL runs whole (Postgres
     * executes the {@code ;}-separated statements in the one rolled-back transaction).
     */
    StudioService.DryRunResult dryRunDdl(String ddl) {
        if (!"postgres".equals(dialect)) {
            return StudioService.DryRunResult.declined("Dry-run is Postgres only — "
                    + (dialect == null ? "unknown" : dialect)
                    + " auto-commits DDL, which can't be rolled back.");
        }
        if (ddl == null || ddl.isBlank()) {
            return StudioService.DryRunResult.declined("No DDL to dry-run.");
        }
        try (Connection connection = sandbox("main").getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(ddl);
            return StudioService.DryRunResult.applied();
        } catch (SQLException ex) {
            return StudioService.DryRunResult.failed(ex.getMessage());
        }
    }

    /** Runs one read query through the sandbox, or null when the binding is not a runnable read. */
    private Map<String, Object> runQuery(Binding binding, Path routeDir,
            Map<String, Object> context) {
        if (binding == null || binding.file() == null || binding.isService() || binding.isContract()
                || binding.isSequence() || !binding.effectiveMode().startsWith("query")) {
            return null;
        }
        Path sqlFile = io.tesseraql.core.files.ConfinedPath.under(appHome)
                .confine(routeDir.resolve(binding.file()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "SQL file escapes app home: " + binding.file()));
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> bindParams = new LinkedHashMap<>();
        binding.params().forEach((name, expr) -> bindParams.put(name,
                evaluation.resolve(Arrays.asList(expr.split("\\.")))));
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(read(sqlFile), functions),
                bindParams);
        DataSource sandbox = sandbox("main");
        try (Connection connection = sandbox.getConnection();
                PreparedStatement statement = connection.prepareStatement(bound.sql())) {
            for (int i = 0; i < bound.parameters().size(); i++) {
                statement.setObject(i + 1, bound.parameters().get(i).value());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> rows = readRows(resultSet);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("rows", rows);
                result.put("rowCount", rows.size());
                return result;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Live query failed: " + ex.getMessage(), ex);
        }
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columns = metaData.getColumnCount();
        JdbcValues.Reader values = JdbcValues
                .reader(metaData);
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(metaData.getColumnLabel(col), io.tesseraql.core.dialect.ResultRows
                        .value(values.read(resultSet, col)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
