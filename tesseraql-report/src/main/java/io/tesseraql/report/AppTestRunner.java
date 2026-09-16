package io.tesseraql.report;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionFunctions;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.coverage.SqlCoverableLines;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.coverage.SqlCoverageReport;
import io.tesseraql.identity.IdentityService;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.test.ManifestCoverage;
import io.tesseraql.test.ManifestSqlFiles;
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
 *
 * <p>Before the first case runs, every SQL file the manifest binds is declared to the coverage
 * collector at 0%, so the population the gate and the regression aggregate read is the
 * application's, not the cases' (docs/audit-low-leads.md G16). And the case names the reports join
 * on are checked once, across every suite: a name declared twice would show the first result for
 * both, and a {@code --case} that matches nothing would have written an all-green overlay for a
 * run of nothing (G19).
 */
public final class AppTestRunner {

    /** Coverage kinds whose gaps are framework-inventory hints rather than test gaps. */
    private static final Set<String> NOTE_KINDS = Set.of("iam-contract", "saml", "oidc",
            "scim", "preference");

    /** TQL-YAML-1410: two cases across the app's suites share a name. */
    static final TqlErrorCode DUPLICATE_CASE_NAME = new TqlErrorCode(TqlDomain.YAML, 1410);

    /** TQL-YAML-1411: a {@code --case} filter named no case. */
    static final TqlErrorCode NO_SUCH_CASE = new TqlErrorCode(TqlDomain.YAML, 1411);

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
        return run(appHome, dataSource, realm, reportDir, Set.of());
    }

    /** As {@link #run(Path, DataSource, RealmConfig, Path)}, resolving custom expression calls
     * against {@code functions}. */
    public RunResult run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir,
            ExpressionFunctions functions) {
        return run(appHome, dataSource, realm, reportDir, Set.of(), functions);
    }

    /**
     * Runs the suites filtered to the named cases (all cases when {@code caseNames} is empty) —
     * the single-case granularity the editor Test Explorer re-runs a failing case with
     * (docs/vscode-extension.md, Phase 56). Item coverage is derived from the filtered suites:
     * a filtered run reports the coverage of what it ran.
     */
    public RunResult run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir,
            Set<String> caseNames) {
        return run(appHome, dataSource, realm, reportDir, caseNames,
                ExpressionFunctions.processDefault());
    }

    /** As {@link #run(Path, DataSource, RealmConfig, Path, Set)}, resolving custom expression
     * calls against {@code functions}. */
    public RunResult run(Path appHome, DataSource dataSource, RealmConfig realm, Path reportDir,
            Set<String> caseNames, ExpressionFunctions functions) {
        IdentityService identity = new IdentityService(name -> dataSource);
        SqlCoverage coverage = new SqlCoverage();
        AppManifest manifest = loadManifest(appHome, functions);
        declareSqlFiles(appHome, manifest, coverage, functions);
        TestRunner runner = new TestRunner(dataSource, appHome, identity, realm, coverage,
                functions);
        TestSuiteLoader loader = new TestSuiteLoader();

        Map<String, Path> namedCases = new java.util.HashMap<>();
        List<TestSuite> loaded = new ArrayList<>();
        for (Path suiteFile : suiteFiles(appHome)) {
            TestSuite suite = loader.load(suiteFile);
            for (TestSuite.TestCase testCase : suite.tests()) {
                Path first = namedCases.putIfAbsent(testCase.name(), suiteFile);
                if (first != null) {
                    throw new TqlException(DUPLICATE_CASE_NAME, "Test case '" + testCase.name()
                            + "' is declared twice: in " + relative(appHome, first) + " and "
                            + relative(appHome, suiteFile) + " — the reports join results to"
                            + " cases by name, so both would show the first result");
                }
            }
            loaded.add(suite);
        }
        if (!caseNames.isEmpty()) {
            Set<String> unknown = new java.util.TreeSet<>(caseNames);
            unknown.removeAll(namedCases.keySet());
            if (!unknown.isEmpty()) {
                throw new TqlException(NO_SUCH_CASE, "No test case is named " + unknown
                        + " under " + appHome.resolve("tests") + " — nothing ran");
            }
        }

        List<TestReport.TestResult> results = new ArrayList<>();
        List<TestSuite> suites = new ArrayList<>();
        for (TestSuite suite : loaded) {
            if (!caseNames.isEmpty()) {
                suite = new TestSuite(suite.tests().stream()
                        .filter(testCase -> caseNames.contains(testCase.name())).toList());
                if (suite.tests().isEmpty()) {
                    continue;
                }
            }
            suites.add(suite);
            results.addAll(runner.run(suite).results());
        }
        List<ItemCoverage> kinds = coverageKinds(manifest, suites);

        TestReport report = new TestReport(results);
        writeReports(report, reportDir);
        writeCoverage(coverage, kinds, reportDir);
        writeSarif(coverage, kinds, reportDir);
        return new RunResult(report, coverage, kinds);
    }

    /**
     * Declares every SQL file the manifest binds to the collector at 0% — the population the
     * gate and the regression aggregate read. A file that is missing or does not parse is lint's
     * finding, not a coverage entry, and is skipped here.
     */
    private static void declareSqlFiles(Path appHome, AppManifest manifest, SqlCoverage coverage,
            ExpressionFunctions functions) {
        Path home = appHome.toAbsolutePath().normalize();
        for (Path file : ManifestSqlFiles.of(manifest)) {
            if (!Files.isRegularFile(file)) {
                continue;
            }
            List<SqlNode> nodes;
            try {
                nodes = Sql2WayParser.parse(Files.readString(file), functions);
            } catch (IOException | RuntimeException unreadable) {
                continue;
            }
            String sqlId = home.relativize(file).toString().replace('\\', '/');
            coverage.declare(sqlId, SqlCoverableLines.compute(nodes),
                    SqlCoverableLines.branchLines(nodes));
        }
    }

    private static String relative(Path appHome, Path file) {
        return appHome.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    /** Derives the item-coverage kinds; the manifest-based ones need a loadable manifest. */
    private static List<ItemCoverage> coverageKinds(AppManifest manifest, List<TestSuite> suites) {
        List<ItemCoverage> kinds = new ArrayList<>();
        kinds.add(SuiteCoverage.assertions(suites));
        kinds.add(SuiteCoverage.contracts(suites));
        if (manifest != null) {
            kinds.add(ManifestCoverage.routes(manifest, suites));
            kinds.add(ManifestCoverage.security(manifest, suites));
            kinds.add(ManifestCoverage.apiKey(manifest, suites));
            kinds.add(ManifestCoverage.mtls(manifest, suites));
            kinds.add(ManifestCoverage.saml(manifest, suites));
            kinds.add(ManifestCoverage.oidc(manifest, suites));
            kinds.add(ManifestCoverage.preference(manifest));
            kinds.add(ManifestCoverage.scim(manifest, suites));
            kinds.add(ManifestCoverage.validation(manifest, suites));
            kinds.add(ManifestCoverage.notification(manifest, suites));
            kinds.add(ManifestCoverage.httpCall(manifest, suites));
            kinds.add(ManifestCoverage.filePoll(manifest, suites));
            kinds.add(ManifestCoverage.webhook(manifest, suites));
            kinds.add(ManifestCoverage.queueConsume(manifest, suites));
            kinds.add(ManifestCoverage.view(manifest, suites));
            kinds.add(ManifestCoverage.page(manifest, suites));
            kinds.add(ManifestCoverage.dataScope(manifest, suites));
            kinds.add(ManifestCoverage.workflow(manifest, suites));
            kinds.add(ManifestCoverage.decision(manifest, suites));
            kinds.add(ManifestCoverage.document(manifest, suites));
            kinds.add(ManifestCoverage.message(manifest, suites));
            kinds.add(ManifestCoverage.mcp(manifest, suites));
            kinds.add(ManifestCoverage.resources(manifest, suites));
            kinds.add(ManifestCoverage.uiResources(manifest, suites));
            kinds.add(ManifestCoverage.prompts(manifest, suites));
        }
        return kinds;
    }

    /**
     * Loads the manifest for the manifest-based coverage kinds. A load failure is not swallowed:
     * returning {@code null} silently dropped ~two dozen {@code ManifestCoverage} kinds from the
     * report, so their {@code coverage.thresholds.*} gates all passed against a report that never
     * measured them. An app whose manifest cannot load has a real error to surface, not coverage
     * to under-report.
     */
    private static AppManifest loadManifest(Path appHome, ExpressionFunctions functions) {
        return new ManifestLoader().load(appHome, functions);
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

    /** Every suite file under {@code tests/}, sorted; empty when the directory is absent. */
    public static List<Path> suiteFiles(Path appHome) {
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
