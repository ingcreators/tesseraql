package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.DocService.DocSpec;
import io.tesseraql.studio.DocService.Hit;
import io.tesseraql.studio.DocService.RouteEntry;
import io.tesseraql.studio.DocService.TestRef;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecModel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocViewsTest {

    private static RouteEntry searchRoute() {
        RouteSpec.Input limit = new RouteSpec.Input("limit", "integer", false, 50, 1, 200, null,
                List.of(), null);
        RouteSpec.SqlStatement sql = new RouteSpec.SqlStatement("sql", "search.sql", null, null,
                "query", "select 1", List.of("q", "limit"),
                List.of(new RouteSpec.Control("if", "q != null", 0)));
        RouteSpec route = new RouteSpec("users.search", "GET", "/api/users", "query-json", "route",
                List.of(limit), new RouteSpec.Security("bearer", "users.read", null, false),
                List.of(), List.of(), new RouteSpec.Response("json", 200, null, null, null),
                List.of(sql));
        return new RouteEntry(route, List.of(new TestRef("finds sato", "sql", "search.sql")));
    }

    @Test
    void indexModelSummarisesRoutesWithDetailLinksAndMigrations() {
        DocSpec spec = new DocSpec(List.of(searchRoute()), List.of(
                new RouteSpecModel.Migration("main", null, "1", "init",
                        "db/migration/V1__init.sql")));

        Map<String, Object> model = DocViews.index("demo", spec, null);

        assertThat(model).containsEntry("appName", "demo").containsEntry("hasRoutes", true)
                .containsEntry("hasMigrations", true).containsEntry("hasReport", false);
        assertThat(asRows(model.get("routes"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "users.search").containsEntry("testCount", 1);
            assertThat((String) row.get("detailUrl")).contains("docs/route?id=users.search");
        });
        assertThat(asRows(model.get("migrations"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("version", "1"));
    }

    @Test
    void routeModelProjectsInputsSecuritySqlStructureAndTests() {
        Map<String, Object> model = DocViews.route(searchRoute(), null);

        assertThat(model).containsEntry("id", "users.search").containsEntry("hasSql", true)
                .containsEntry("hasTests", true).containsEntry("hasReport", false);
        assertThat(asMap(model.get("security"))).containsEntry("auth", "bearer")
                .containsEntry("csrf", false);
        assertThat(asRows(model.get("inputs"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("name", "limit");
            assertThat(asStrings(row.get("constraints"))).contains("default 50", "min 1",
                    "max 200");
        });
        assertThat(asRows(model.get("sql"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("file", "search.sql");
            assertThat(asStrings(row.get("binds"))).containsExactly("q", "limit");
            assertThat(asRows(row.get("structure"))).singleElement()
                    .satisfies(node -> assertThat(node).containsEntry("kind", "if")
                            .containsEntry("expression", "q != null"));
        });
        assertThat(asRows(model.get("tests"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("kind", "sql"));
    }

    private static ReportOverlay overlayForSearch() {
        ReportOverlay.RouteReport report = new ReportOverlay.RouteReport(true,
                List.of(new ReportOverlay.CaseResult("finds sato", true, "OK")),
                List.of(new ReportOverlay.SqlFileCoverage("web/api/users/search.sql", 0.5, 1.0, 1,
                        2,
                        List.of(1), List.of(1, 2))),
                Map.of());
        return new ReportOverlay(1, "run-1", "2026-06-15T12:00:00Z",
                new ReportOverlay.Summary(1, 1, 0, 0.5, 1.0, true),
                new ReportOverlay.Thresholds(0.0, 0.0, Map.of()),
                new ReportOverlay.Gate(true, List.of()), List.of(),
                Map.of("users.search", report));
    }

    @Test
    void indexOverlaysRunStatusAndCoverageWhenAReportIsPresent() {
        DocSpec spec = new DocSpec(List.of(searchRoute()), List.of());

        Map<String, Object> model = DocViews.index("demo", spec, overlayForSearch());

        assertThat(model).containsEntry("hasReport", true);
        assertThat(asMap(model.get("report"))).containsEntry("passed", 1L)
                .containsEntry("total", 1).containsEntry("sqlLinePct", 50)
                .containsEntry("gatePassed", true);
        assertThat(asRows(model.get("routes"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("hasReport", true).containsEntry("covered", true)
                    .containsEntry("testsPassed", 1L).containsEntry("testsRun", 1)
                    .containsEntry("allPassed", true).containsEntry("linePct", 50);
        });
    }

    @Test
    void routeMergesTestResultsAndSqlCoverageFromTheOverlay() {
        ReportOverlay.RouteReport report = overlayForSearch().routeReport("users.search");

        Map<String, Object> model = DocViews.route(searchRoute(), report);

        assertThat(model).containsEntry("hasReport", true).containsEntry("covered", true)
                .containsEntry("testsPassed", 1L).containsEntry("sqlLinePct", 50)
                .containsEntry("sqlBranchPct", 100);
        assertThat(asRows(model.get("tests"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("passed", true));
        assertThat(asRows(model.get("sql"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("linePct", 50).containsEntry("branchPct", 100));
    }

    @Test
    void searchResultsModelMapsHitsToDetailLinks() {
        Map<String, Object> model = DocViews.searchResults("user",
                List.of(new Hit("users.search", "GET", "/api/users", 2)));

        assertThat(model).containsEntry("query", "user").containsEntry("hasResults", true);
        assertThat(asRows(model.get("results"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "users.search").containsEntry("method", "GET");
            assertThat((String) row.get("detailUrl")).contains("docs/route?id=users.search");
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asRows(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStrings(Object value) {
        return (List<String>) value;
    }
}
