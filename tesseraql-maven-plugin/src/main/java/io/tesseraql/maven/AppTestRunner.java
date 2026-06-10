package io.tesseraql.maven;

import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.HtmlReporter;
import io.tesseraql.report.JUnitXmlReporter;
import io.tesseraql.report.JsonReporter;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestRunner;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuiteLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Discovers and runs declarative test suites under an app's {@code tests/} directory and writes the
 * JUnit XML, JSON, and HTML reports (design ch. 13, 15, 18). Independent of Maven for testability.
 */
public final class AppTestRunner {

    /** Result of a test run: the aggregated report and the collected SQL coverage. */
    public record RunResult(TestReport report, SqlCoverage coverage) {
    }

    /** Runs every {@code tests/**}{@code /*.yml} suite and writes reports under {@code reportDir}. */
    public RunResult run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir) {
        IdentityService identity = new IdentityService(name -> dataSource);
        SqlCoverage coverage = new SqlCoverage();
        TestRunner runner = new TestRunner(dataSource, appHome, identity, realm, coverage);
        TestSuiteLoader loader = new TestSuiteLoader();

        List<TestReport.TestResult> results = new ArrayList<>();
        for (Path suiteFile : suiteFiles(appHome)) {
            TestSuite suite = loader.load(suiteFile);
            results.addAll(runner.run(suite).results());
        }
        TestReport report = new TestReport(results);
        writeReports(report, reportDir);
        writeCoverage(coverage, reportDir);
        return new RunResult(report, coverage);
    }

    private static List<Path> suiteFiles(Path appHome) {
        Path testsDir = appHome.resolve("tests");
        if (!Files.isDirectory(testsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(testsDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void writeCoverage(SqlCoverage coverage, Path reportDir) {
        StringBuilder json = new StringBuilder("{\n  \"sql\": {\n");
        var reports = coverage.reports();
        int i = 0;
        for (Map.Entry<String, SqlCoverageReport> entry : reports.entrySet()) {
            SqlCoverageReport report = entry.getValue();
            json.append("    \"").append(entry.getKey()).append("\": {")
                    .append("\"branchRatio\": ").append(report.branchRatio())
                    .append(", \"branchCount\": ").append(report.branchCount())
                    .append(", \"lineRatio\": ").append(report.lineRatio())
                    .append(", \"coverableLines\": ").append(report.coverableLineCount())
                    .append(", \"lines\": ").append(report.lineCount()).append("}");
            json.append(++i < reports.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        try {
            Files.createDirectories(reportDir.resolve("coverage"));
            Files.writeString(reportDir.resolve("coverage/sql-coverage.json"), json.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void writeReports(TestReport report, Path reportDir) {
        try {
            Files.createDirectories(reportDir.resolve("junit"));
            Files.writeString(reportDir.resolve("junit/TEST-tesseraql.xml"),
                    JUnitXmlReporter.toXml(report, "tesseraql"));
            Files.writeString(reportDir.resolve("tesseraql-result.json"), JsonReporter.toJson(report));
            Files.writeString(reportDir.resolve("index.html"), HtmlReporter.toHtml(report, "TesseraQL Tests"));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
