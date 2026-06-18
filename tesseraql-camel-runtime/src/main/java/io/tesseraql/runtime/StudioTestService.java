package io.tesseraql.runtime;

import io.tesseraql.test.CrossReferenceIndex;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestRunner;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuite.TestCase;
import io.tesseraql.test.TestSuiteLoader;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import io.tesseraql.yaml.manifest.RouteFile;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Runs a route's declarative {@code sql} test cases from Studio against the dev datasource and
 * returns an inline result model (Studio backlog A2 — "run tests now" in the editor).
 *
 * <p>Gated and sandboxed (decided with the maintainer): enabled only when Studio is writable and
 * {@code tesseraql.studio.testRunner.enabled} is set; every case runs through a
 * {@link SandboxDataSource} (read-only connection, statement timeout, row cap, rollback on close),
 * so a query can neither run away nor persist a write. Only read-only {@code sql} cases run this
 * slice — {@code validate}/{@code notify}/{@code http-call} and write paths are out of scope.
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
        RouteFile route = manifest.routes().stream()
                .filter(file -> relativePath.equals(relative(file.source())))
                .findFirst()
                .orElse(null);
        if (route == null) {
            return notRun(relativePath, "No route is declared at " + relativePath + ".");
        }
        List<TestCase> sqlCases = CrossReferenceIndex.of(manifest, loadSuites()).casesFor(route)
                .stream()
                .filter(StudioTestService::isSqlCase)
                .toList();
        if (sqlCases.isEmpty()) {
            return notRun(relativePath,
                    "No read-only SQL test cases cover this route.");
        }
        DataSource sandbox = new SandboxDataSource(dataSource, queryTimeoutSeconds, maxRows);
        TestReport report = new TestRunner(sandbox, appHome).run(new TestSuite(sqlCases));
        return result(relativePath, report);
    }

    /** A read-only query case: a {@code sql:} target with no validate/notify/http/messages/contract. */
    private static boolean isSqlCase(TestCase test) {
        return test.sql() != null && test.sql().file() != null
                && test.validate() == null && test.notifications() == null
                && test.httpCall() == null && test.messages() == null
                && (test.contract() == null || test.contract().isBlank());
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
}
