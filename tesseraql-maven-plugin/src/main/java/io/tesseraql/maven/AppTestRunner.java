package io.tesseraql.maven;

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
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Discovers and runs declarative test suites under an app's {@code tests/} directory and writes the
 * JUnit XML, JSON, and HTML reports (design ch. 13, 15, 18). Independent of Maven for testability.
 */
public final class AppTestRunner {

    /** Runs every {@code tests/**}{@code /*.yml} suite and writes reports under {@code reportDir}. */
    public TestReport run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir) {
        IdentityService identity = new IdentityService(name -> dataSource);
        TestRunner runner = new TestRunner(dataSource, appHome, identity, realm);
        TestSuiteLoader loader = new TestSuiteLoader();

        List<TestReport.TestResult> results = new ArrayList<>();
        for (Path suiteFile : suiteFiles(appHome)) {
            TestSuite suite = loader.load(suiteFile);
            results.addAll(runner.run(suite).results());
        }
        TestReport report = new TestReport(results);
        writeReports(report, reportDir);
        return report;
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
