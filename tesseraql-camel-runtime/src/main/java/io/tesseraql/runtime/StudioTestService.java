package io.tesseraql.runtime;

import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.core.sql.BoundSql;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlRenderer;
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
import io.tesseraql.yaml.model.PipelineStep;
import io.tesseraql.yaml.model.RouteDefinition;
import io.tesseraql.yaml.model.SqlBinding;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * write. The declarative case kinds run: {@code sql} queries and {@code validate} rules (their SQL
 * runs against the sandbox), {@code notify} and {@code http-call} evaluations (pure, no DB; the
 * latter plans a job's outbound step without a network call), and {@code sql} write cases (an
 * {@code INSERT … RETURNING} executes and its rows are checked, then rolled back). Contract cases
 * are out of scope — they execute through the runtime's identity datasource, not the sandbox.
 */
final class StudioTestService {

    private final DataSource dataSource;
    private final Path appHome;
    private final boolean enabled;
    private final int queryTimeoutSeconds;
    private final int maxRows;

    StudioTestService(DataSource dataSource, Path appHome, boolean enabled,
            int queryTimeoutSeconds, int maxRows) {
        this.dataSource = dataSource;
        this.appHome = appHome.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
        this.maxRows = maxRows;
    }

    boolean isEnabled() {
        return enabled;
    }

    /**
     * Runs the read-only {@code sql} test cases covering the route at {@code relativePath} and
     * returns a template-ready result model. A disabled runner, an unknown route, or a route with no
     * SQL cases each comes back as a {@code ran: false} model carrying an explanatory note.
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
        DataSource sandbox = new SandboxDataSource(dataSource, queryTimeoutSeconds, maxRows);
        TestReport report = new TestRunner(sandbox, appHome).run(new TestSuite(runnable));
        return result(relativePath, report);
    }

    /**
     * The test cases covering a job: {@code notify}/{@code http-call} cases targeting it by id, and
     * {@code sql} cases exercising one of its (main or step) SQL files. Mirrors
     * {@link CrossReferenceIndex#casesFor} for routes, which the index does not provide for jobs.
     */
    private List<TestCase> casesForJob(JobFile job, List<TestSuite> suites) {
        String jobId = job.definition().id();
        Path jobDir = job.source().getParent();
        Set<Path> sqlFiles = new LinkedHashSet<>();
        addSqlFile(sqlFiles, jobDir, job.definition().sql());
        for (PipelineStep step : job.definition().effectiveSteps()) {
            addSqlFile(sqlFiles, jobDir, step.sql());
        }
        List<TestCase> cases = new ArrayList<>();
        for (TestSuite suite : suites) {
            for (TestCase test : suite.tests()) {
                if (linksJob(test, jobId, sqlFiles)) {
                    cases.add(test);
                }
            }
        }
        return cases;
    }

    private void addSqlFile(Set<Path> into, Path jobDir, SqlBinding binding) {
        if (binding != null && binding.file() != null) {
            into.add(jobDir.resolve(binding.file()).normalize());
        }
    }

    private boolean linksJob(TestCase test, String jobId, Set<Path> sqlFiles) {
        if (test.notifications() != null && jobId.equals(test.notifications().job())) {
            return true;
        }
        if (test.httpCall() != null && jobId.equals(test.httpCall().job())) {
            return true;
        }
        return test.sql() != null && test.sql().file() != null
                && sqlFiles.contains(appHome.resolve(test.sql().file()).normalize());
    }

    /**
     * A declarative case the sandbox can run: a {@code sql} query or write (its SQL runs against the
     * sandbox — a write executes and is rolled back), a {@code validate} rule, a {@code notify}
     * evaluation, or an {@code http-call} plan (the last two are pure, no DB). Contract cases are
     * excluded — they run through the runtime's identity datasource, not the sandbox — and
     * {@code messages} cases carry no DB/route/job binding.
     */
    private static boolean isRunnable(TestCase test) {
        if (test.contract() != null && !test.contract().isBlank()) {
            return false;
        }
        return (test.sql() != null && test.sql().file() != null)
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
     * Executes a route's main {@code sql} query against the sandbox and returns its rows as the
     * {@code sql} result for the rendered preview (Studio backlog A1 "real bound params"): a
     * {@link StudioService.RowSource}. The bind params resolve from the render context (the sample's
     * {@code params}/{@code query}); the same {@link SandboxDataSource} guards apply (read-only,
     * timeout, row cap, rollback). Returns null — keeping the hand-authored sample — when the route
     * has no runnable read query (a service/contract/sequence binding, a non-{@code query} mode, or
     * no SQL file).
     */
    Map<String, Object> liveRows(RouteDefinition route, Path routeDir,
            Map<String, Object> context) {
        SqlBinding sql = route.sql();
        if (sql == null || sql.file() == null || sql.isService() || sql.isContract()
                || sql.isSequence() || !sql.effectiveMode().startsWith("query")) {
            return null;
        }
        Path sqlFile = routeDir.resolve(sql.file()).normalize();
        if (!sqlFile.startsWith(appHome)) {
            throw new IllegalArgumentException("SQL file escapes app home: " + sql.file());
        }
        EvaluationContext evaluation = new EvaluationContext(context);
        Map<String, Object> bindParams = new LinkedHashMap<>();
        sql.params().forEach((name, expr) -> bindParams.put(name,
                evaluation.resolve(Arrays.asList(expr.split("\\.")))));
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(read(sqlFile)), bindParams);
        DataSource sandbox = new SandboxDataSource(dataSource, queryTimeoutSeconds, maxRows);
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
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int col = 1; col <= columns; col++) {
                row.put(metaData.getColumnLabel(col), resultSet.getObject(col));
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
