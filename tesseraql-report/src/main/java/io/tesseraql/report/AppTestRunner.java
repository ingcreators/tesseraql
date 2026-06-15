package io.tesseraql.report;

import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.ManifestCoverage;
import io.tesseraql.test.SuiteCoverage;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestRunner;
import io.tesseraql.test.TestSuite;
import io.tesseraql.test.TestSuiteLoader;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import javax.sql.DataSource;

/**
 * Discovers and runs declarative test suites under an app's {@code tests/} directory and writes the
 * JUnit XML, JSON, HTML, SARIF, Cobertura, SonarQube, and Allure reports (design ch. 13, 15, 18).
 * Independent of Maven for testability.
 */
public final class AppTestRunner {

    /** Coverage kinds whose gaps are framework-inventory hints rather than test gaps. */
    private static final Set<String> NOTE_KINDS = Set.of("iam-contract", "saml", "oidc", "scim");

    /**
     * Result of a test run: the aggregated report, the collected SQL coverage, and the derived
     * item-coverage kinds (assertion, iam-contract, route, security, api-key, mtls, saml, oidc,
     * scim, validation, notification, http-call, file-poll, webhook, document, message — design
     * ch. 14, roadmap Phases 19-26).
     */
    public record RunResult(TestReport report, SqlCoverage coverage, List<ItemCoverage> kinds) {

        public RunResult {
            kinds = List.copyOf(kinds);
        }

        /** The derived coverage of one kind, or {@code null} when it was not collected. */
        public ItemCoverage kind(String name) {
            return kinds.stream().filter(kind -> kind.kind().equals(name)).findFirst().orElse(null);
        }
    }

    /** Runs every {@code tests/**}{@code /*.yml} suite and writes reports under {@code reportDir}. */
    public RunResult run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir) {
        IdentityService identity = new IdentityService(name -> dataSource);
        SqlCoverage coverage = new SqlCoverage();
        TestRunner runner = new TestRunner(dataSource, appHome, identity, realm, coverage);
        TestSuiteLoader loader = new TestSuiteLoader();

        List<TestReport.TestResult> results = new ArrayList<>();
        List<TestSuite> suites = new ArrayList<>();
        for (Path suiteFile : suiteFiles(appHome)) {
            TestSuite suite = loader.load(suiteFile);
            suites.add(suite);
            results.addAll(runner.run(suite).results());
        }
        List<ItemCoverage> kinds = coverageKinds(appHome, suites);

        TestReport report = new TestReport(results);
        writeReports(report, reportDir);
        writeCoverage(coverage, kinds, reportDir);
        writeSarif(coverage, kinds, reportDir);
        return new RunResult(report, coverage, kinds);
    }

    /** Derives the item-coverage kinds; the manifest-based ones need a loadable manifest. */
    private static List<ItemCoverage> coverageKinds(Path appHome, List<TestSuite> suites) {
        List<ItemCoverage> kinds = new ArrayList<>();
        kinds.add(SuiteCoverage.assertions(suites));
        kinds.add(SuiteCoverage.contracts(suites));
        AppManifest manifest = loadManifest(appHome);
        if (manifest != null) {
            kinds.add(ManifestCoverage.routes(manifest, suites));
            kinds.add(ManifestCoverage.security(manifest, suites));
            kinds.add(ManifestCoverage.apiKey(manifest, suites));
            kinds.add(ManifestCoverage.mtls(manifest, suites));
            kinds.add(ManifestCoverage.saml(manifest, suites));
            kinds.add(ManifestCoverage.oidc(manifest, suites));
            kinds.add(ManifestCoverage.scim(manifest, suites));
            kinds.add(ManifestCoverage.validation(manifest, suites));
            kinds.add(ManifestCoverage.notification(manifest, suites));
            kinds.add(ManifestCoverage.httpCall(manifest, suites));
            kinds.add(ManifestCoverage.filePoll(manifest, suites));
            kinds.add(ManifestCoverage.webhook(manifest, suites));
            kinds.add(ManifestCoverage.queueConsume(manifest, suites));
            kinds.add(ManifestCoverage.dataScope(manifest, suites));
            kinds.add(ManifestCoverage.document(manifest, suites));
            kinds.add(ManifestCoverage.message(manifest, suites));
            kinds.add(ManifestCoverage.mcp(manifest, suites));
            kinds.add(ManifestCoverage.resources(manifest, suites));
            kinds.add(ManifestCoverage.uiResources(manifest, suites));
        }
        return kinds;
    }

    private static AppManifest loadManifest(Path appHome) {
        try {
            return new ManifestLoader().load(appHome);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Writes coverage gaps as SARIF so CI code-scanning can annotate them (design ch. 15). */
    private static void writeSarif(SqlCoverage coverage, List<ItemCoverage> kinds, Path reportDir) {
        List<SarifReporter.Finding> findings = new ArrayList<>();
        coverage.reports().forEach((sqlId, report) -> {
            if (report.branchRatio() < 1.0) {
                findings.add(new SarifReporter.Finding("sql-branch-coverage", "warning",
                        String.format("Branch coverage %.0f%% for %s", report.branchRatio() * 100,
                                sqlId),
                        sqlId, null));
            }
            if (report.lineRatio() < 1.0) {
                findings.add(new SarifReporter.Finding("sql-line-coverage", "warning",
                        String.format("Line coverage %.0f%% (%d/%d) for %s",
                                report.lineRatio() * 100,
                                report.lineCount(), report.coverableLineCount(), sqlId),
                        sqlId, null));
            }
        });
        for (ItemCoverage kind : kinds) {
            String level = NOTE_KINDS.contains(kind.kind()) ? "note" : "warning";
            for (String item : kind.uncovered()) {
                findings.add(new SarifReporter.Finding(kind.kind() + "-coverage", level,
                        kind.kind() + " not covered: " + item, null, null));
            }
        }
        try {
            Files.createDirectories(reportDir.resolve("coverage"));
            Files.writeString(reportDir.resolve("coverage/coverage.sarif"),
                    SarifReporter.toSarif("tesseraql", findings));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
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

    private static void writeCoverage(SqlCoverage coverage, List<ItemCoverage> kinds,
            Path reportDir) {
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
        json.append("  },\n  \"kinds\": {\n");
        for (int k = 0; k < kinds.size(); k++) {
            ItemCoverage kind = kinds.get(k);
            json.append("    \"").append(kind.kind()).append("\": {")
                    .append("\"ratio\": ").append(kind.ratio())
                    .append(", \"covered\": ").append(kind.covered().size())
                    .append(", \"declared\": ").append(kind.declared().size())
                    .append(", \"uncovered\": ").append(jsonArray(kind.uncovered())).append("}");
            json.append(k + 1 < kinds.size() ? ",\n" : "\n");
        }
        json.append("  }\n}\n");
        try {
            Files.createDirectories(reportDir.resolve("coverage"));
            Files.writeString(reportDir.resolve("coverage/sql-coverage.json"), json.toString());
            Files.writeString(reportDir.resolve("coverage/cobertura.xml"),
                    CoberturaReporter.toXml(reports));
            Files.writeString(reportDir.resolve("coverage/sonarqube.xml"),
                    SonarQubeReporter.toXml(reports));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String jsonArray(java.util.Collection<String> values) {
        StringBuilder out = new StringBuilder("[");
        int i = 0;
        for (String value : values) {
            out.append('"').append(value.replace("\"", "\\\"")).append('"');
            if (++i < values.size()) {
                out.append(", ");
            }
        }
        return out.append(']').toString();
    }

    private static void writeReports(TestReport report, Path reportDir) {
        try {
            Files.createDirectories(reportDir.resolve("junit"));
            Files.writeString(reportDir.resolve("junit/TEST-tesseraql.xml"),
                    JUnitXmlReporter.toXml(report, "tesseraql"));
            Files.writeString(reportDir.resolve("tesseraql-result.json"),
                    JsonReporter.toJson(report));
            Files.writeString(reportDir.resolve("index.html"),
                    HtmlReporter.toHtml(report, "TesseraQL Tests"));
            Path allureDir = reportDir.resolve("allure-results");
            Files.createDirectories(allureDir);
            for (Map.Entry<String, String> file : AllureReporter.toResults(report, "tesseraql")
                    .entrySet()) {
                Files.writeString(allureDir.resolve(file.getKey()), file.getValue());
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
