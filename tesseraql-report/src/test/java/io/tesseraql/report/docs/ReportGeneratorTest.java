package io.tesseraql.report.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.coverage.ItemCoverage;
import io.tesseraql.coverage.SqlCoverage;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.report.docs.ReportDoc.CaseResult;
import io.tesseraql.test.TestReport;
import io.tesseraql.test.TestReport.TestResult;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage of the report-overlay joins (route&rarr;test&rarr;result, route&rarr;item coverage,
 * summary, gate, JSON) over the example app's manifest and a fabricated run. SQL line-set coverage,
 * which needs a real render, is asserted by the Testcontainers integration test.
 */
class ReportGeneratorTest {

    private static final AppManifest MANIFEST = manifest();

    private static AppManifest manifest() {
        Path app = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(app);
    }

    private static AppTestRunner.RunResult run() {
        TestReport report = new TestReport(List.of(
                TestResult.pass("search finds sato by name"),
                TestResult.fail("search without query returns all users", "boom")));
        ItemCoverage routeKind = new ItemCoverage("route");
        routeKind.declare("users.search").declare("users.apiProvision").cover("users.search");
        ItemCoverage validation = new ItemCoverage("validation");
        validation.declare("users.apiProvision.userExists").cover("users.apiProvision.userExists");
        return new AppTestRunner.RunResult(report, new SqlCoverage(),
                List.of(routeKind, validation));
    }

    private static ReportDoc generate() {
        return new ReportGenerator().generate(MANIFEST, run(),
                new CoverageThresholds(0.0, 0.0), "run-1", "2026-06-15T12:00:00Z");
    }

    @Test
    void stampsTheRunIdentityAndSchemaVersion() {
        ReportDoc doc = generate();

        assertThat(doc.schemaVersion()).isEqualTo(ReportDoc.SCHEMA_VERSION);
        assertThat(doc.runId()).isEqualTo("run-1");
        assertThat(doc.generatedAt()).isEqualTo("2026-06-15T12:00:00Z");
    }

    @Test
    void summarisesTheRunTotals() {
        ReportDoc doc = generate();

        assertThat(doc.summary().total()).isEqualTo(2);
        assertThat(doc.summary().passed()).isEqualTo(1);
        assertThat(doc.summary().failed()).isEqualTo(1);
        // No SQL coverage recorded in this fabricated run -> ratios default to 1.0.
        assertThat(doc.summary().sqlLineRatio()).isEqualTo(1.0);
        assertThat(doc.summary().gatePassed()).isTrue();
    }

    @Test
    void joinsCoveringTestCasesToTheirResultsByName() {
        ReportDoc.RouteReport search = generate().routes().get("users.search");

        assertThat(search.covered()).isTrue();
        assertThat(search.tests())
                .extracting(CaseResult::name, CaseResult::passed)
                .containsExactly(
                        tuple("search finds sato by name", true),
                        tuple("search without query returns all users", false));
    }

    @Test
    void marksRoutesTheRouteKindDidNotCover() {
        assertThat(generate().routes().get("users.apiProvision").covered()).isFalse();
    }

    @Test
    void restrictsPerRouteItemCoverageToItemsNamingTheRoute() {
        ReportDoc.RouteReport provision = generate().routes().get("users.apiProvision");

        assertThat(provision.itemCoverage()).containsEntry("validation", 1.0);
        // The route's own coverage flag is not duplicated as an item-coverage kind.
        assertThat(provision.itemCoverage()).doesNotContainKey("route");
        // users.search is named by no validation item, so it carries none.
        assertThat(generate().routes().get("users.search").itemCoverage())
                .doesNotContainKey("validation");
    }

    @Test
    void exposesEveryItemCoverageKind() {
        assertThat(generate().kinds())
                .extracting(ReportDoc.KindCoverage::kind)
                .contains("route", "validation");
    }

    @Test
    void serialisesToParseableJson() {
        ReportGenerator generator = new ReportGenerator();
        ReportDoc doc = generate();

        String json = generator.toJson(doc);

        assertThat(json).contains("\"schemaVersion\"").contains("\"users.search\"")
                .contains("\"runId\" : \"run-1\"");
    }
}
