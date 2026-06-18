package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.studio.DocService.DocSpec;
import io.tesseraql.studio.DocService.Hit;
import io.tesseraql.studio.DocService.RouteEntry;
import io.tesseraql.studio.DocService.TestRef;
import io.tesseraql.yaml.docs.RouteSpec;
import io.tesseraql.yaml.docs.RouteSpecModel;
import io.tesseraql.yaml.scaffold.CatalogSchema;
import java.sql.Types;
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
    void routeBuildsPerLineSqlCoverageForHighlighting() {
        RouteSpec.SqlStatement sql = new RouteSpec.SqlStatement("sql", "q.sql", null, null, "query",
                "line1\nline2\nline3", List.of(), List.of());
        RouteSpec route = new RouteSpec("r", "GET", "/p", "query-json", "route", List.of(), null,
                List.of(), List.of(), null, List.of(sql));
        ReportOverlay.RouteReport report = new ReportOverlay.RouteReport(true, List.of(),
                List.of(new ReportOverlay.SqlFileCoverage("web/q.sql", 0.5, 1.0, 0, 0, List.of(1),
                        List.of(1, 2))),
                Map.of());

        Map<String, Object> model = DocViews.route(new RouteEntry(route, List.of()), report);

        List<Map<String, Object>> lines = asRows(asRows(model.get("sql")).get(0).get("lines"));
        assertThat(lines).extracting(line -> line.get("state"))
                .containsExactly("covered", "missed", "plain");
        // Each line also carries server-highlighted HTML (plain here — no SQL keywords).
        assertThat(lines.get(0)).containsEntry("number", 1).containsEntry("text", "line1")
                .containsEntry("html", "line1");
    }

    @Test
    void coverageModelSummarisesKindsGateAndFailingCases() {
        ReportOverlay.RouteReport search = new ReportOverlay.RouteReport(true,
                List.of(new ReportOverlay.CaseResult("ok", true, "OK"),
                        new ReportOverlay.CaseResult("broken", false, "boom")),
                List.of(), Map.of());
        ReportOverlay overlay = new ReportOverlay(1, "run-1", "2026-06-15T12:00:00Z",
                new ReportOverlay.Summary(2, 1, 1, 0.5, 1.0, false),
                new ReportOverlay.Thresholds(0.8, 0.7, Map.of("route", 1.0)),
                new ReportOverlay.Gate(false,
                        List.of("web/x.sql: line coverage 50% < required 80%")),
                List.of(new ReportOverlay.KindCoverage("route", 0.5, 1, 2, List.of("users.print"))),
                Map.of("users.search", search));

        Map<String, Object> model = DocViews.coverage("demo", overlay, List.of());

        assertThat(model).containsEntry("hasReport", true).containsEntry("hasKinds", true)
                .containsEntry("hasGateFailures", true).containsEntry("hasFailingCases", true);
        assertThat(asRows(model.get("kinds"))).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("kind", "route").containsEntry("pct", 50)
                .containsEntry("full", false));
        assertThat(asRows(model.get("failingCases"))).singleElement().satisfies(row -> assertThat(
                row).containsEntry("route", "users.search").containsEntry("name", "broken"));
    }

    @Test
    void coverageModelIsEmptyWithoutAnOverlay() {
        Map<String, Object> model = DocViews.coverage("demo", null, List.of());

        assertThat(model).containsEntry("hasReport", false).doesNotContainKey("kinds");
    }

    private static ReportOverlay passingOverlay() {
        return new ReportOverlay(1, "r", "t", new ReportOverlay.Summary(2, 2, 0, 1.0, 1.0, true),
                new ReportOverlay.Thresholds(0.0, 0.0, Map.of()),
                new ReportOverlay.Gate(true, List.of()), List.of(), Map.of());
    }

    @Test
    void coverageModelBuildsSparklineTrendFromHistory() {
        List<DocService.HistoryPoint> history = List.of(
                new DocService.HistoryPoint("r1", "t1", 2, 1, 1, 0.5, 0.5, false),
                new DocService.HistoryPoint("r2", "t2", 2, 2, 0, 1.0, 1.0, true));

        Map<String, Object> model = DocViews.coverage("demo", passingOverlay(), history);

        Map<String, Object> trend = asMap(model.get("trend"));
        assertThat(trend).containsEntry("runs", 2).containsEntry("passPct", 100)
                .containsEntry("linePct", 100);
        // Two runs -> a raw ratio series for hc-sparkline data-values: 50% then 100%.
        assertThat((String) trend.get("passSpark")).isEqualTo("0.5,1");
    }

    @Test
    void coverageModelHasNoTrendWithFewerThanTwoRuns() {
        Map<String, Object> model = DocViews.coverage("demo", passingOverlay(),
                List.of(new DocService.HistoryPoint("r", "t", 2, 2, 0, 1.0, 1.0, true)));

        assertThat(model.get("trend")).isNull();
    }

    @Test
    void searchResultsModelMapsHitsToDetailLinks() {
        Map<String, Object> model = DocViews.searchResults("user", List.of(new Hit("users.search",
                "GET", "/api/users", "/_tesseraql/studio/ui/docs/route?id=users.search", 2)));

        assertThat(model).containsEntry("query", "user").containsEntry("hasResults", true);
        assertThat(asRows(model.get("results"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "users.search").containsEntry("method", "GET");
            assertThat((String) row.get("detailUrl")).contains("docs/route?id=users.search");
        });
    }

    @Test
    void searchResultsModelLinksTableHitsToTablePages() {
        Map<String, Object> model = DocViews.searchResults("customers", List.of(new Hit("customers",
                "TABLE", "main", "/_tesseraql/studio/ui/docs/schema/table?ds=main&name=customers",
                1)));

        assertThat(asRows(model.get("results"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "customers").containsEntry("method", "TABLE");
            assertThat((String) row.get("detailUrl"))
                    .contains("schema/table?ds=main&name=customers");
        });
    }

    private static SchemaOverlay sampleSchema() {
        CatalogSchema.Table customers = new CatalogSchema.Table("customers", "TABLE", "public",
                List.of(new CatalogSchema.Column("id", Types.BIGINT, "bigserial", 19, false, true,
                        "nextval('customers_id_seq')"),
                        new CatalogSchema.Column("email", Types.VARCHAR, "varchar", 320, false,
                                false, null)),
                List.of("id"), List.of(),
                List.of(new CatalogSchema.Index("customers_email_key", List.of("email"), true)));
        CatalogSchema.Table orders = new CatalogSchema.Table("orders", "TABLE", "public",
                List.of(new CatalogSchema.Column("id", Types.BIGINT, "bigserial", 19, false, true,
                        null),
                        new CatalogSchema.Column("customer_id", Types.BIGINT, "bigint", 19, false,
                                false, null)),
                List.of("id"),
                List.of(new CatalogSchema.ForeignKey("orders_customer_id_fkey",
                        List.of("customer_id"), "customers", List.of("id"))),
                List.of());
        return new SchemaOverlay(1, "2026-06-15T12:00:00Z",
                Map.of("main", new CatalogSchema(List.of(customers, orders))));
    }

    @Test
    void schemaModelListsDatasourcesAndTablesWithLinks() {
        Map<String, Object> model = DocViews.schema("demo", sampleSchema());

        assertThat(model).containsEntry("appName", "demo").containsEntry("hasSchema", true);
        assertThat(asRows(model.get("datasources"))).singleElement().satisfies(ds -> {
            assertThat(ds).containsEntry("name", "main").containsEntry("tableCount", 2);
            assertThat(asRows(ds.get("tables"))).anySatisfy(table -> {
                assertThat(table).containsEntry("name", "customers").containsEntry("type", "TABLE")
                        .containsEntry("columnCount", 2).containsEntry("primaryKey", "id");
                assertThat((String) table.get("detailUrl"))
                        .contains("schema/table?ds=main&name=customers");
            });
        });
    }

    @Test
    void schemaModelIsEmptyWithoutAnOverlay() {
        assertThat(DocViews.schema("demo", null)).containsEntry("hasSchema", false)
                .doesNotContainKey("datasources");
    }

    @Test
    void tableModelProjectsColumnsKeysForeignKeysAndIndexes() {
        CatalogSchema.Table orders = sampleSchema().datasource("main").tables().stream()
                .filter(table -> table.name().equals("orders")).findFirst().orElseThrow();

        Map<String, Object> model = DocViews.table("main", orders);

        assertThat(model).containsEntry("name", "orders").containsEntry("type", "TABLE")
                .containsEntry("primaryKey", "id").containsEntry("hasForeignKeys", true);
        assertThat(asRows(model.get("columns"))).anySatisfy(column -> assertThat(column)
                .containsEntry("name", "id").containsEntry("primaryKey", true)
                .containsEntry("autoincrement", true));
        assertThat(asRows(model.get("foreignKeys"))).singleElement().satisfies(fk -> {
            assertThat(fk).containsEntry("columns", "customer_id")
                    .containsEntry("refTable", "customers").containsEntry("refColumns", "id");
            assertThat((String) fk.get("refUrl")).contains("schema/table?ds=main&name=customers");
        });
    }

    @Test
    void tableModelRendersCharacterTypeLengthAndUniqueIndex() {
        CatalogSchema.Table customers = sampleSchema().datasource("main").tables().stream()
                .filter(table -> table.name().equals("customers")).findFirst().orElseThrow();

        Map<String, Object> model = DocViews.table("main", customers);

        assertThat(asRows(model.get("columns"))).anySatisfy(column -> assertThat(column)
                .containsEntry("name", "email").containsEntry("type", "varchar(320)"));
        assertThat(model).containsEntry("hasUniqueIndexes", true);
        assertThat(asRows(model.get("uniqueIndexes"))).singleElement()
                .satisfies(index -> assertThat(index).containsEntry("columns", "email"));
    }

    @Test
    void exportModelListsTheDownloadableSpecArtifacts() {
        Map<String, Object> model = DocViews.export("demo");

        assertThat(model).containsEntry("appName", "demo");
        assertThat(asRows(model.get("artifacts"))).hasSize(2);
        assertThat(asRows(model.get("artifacts"))).anySatisfy(row -> {
            assertThat(row).containsEntry("filename", "openapi.json")
                    .containsEntry("url", "/_tesseraql/studio/ui/docs/export/openapi");
            assertThat((String) row.get("description")).isNotBlank();
        });
        assertThat(asRows(model.get("artifacts")))
                .anySatisfy(row -> assertThat(row).containsEntry("filename", "htmx-contract.json")
                        .containsEntry("url", "/_tesseraql/studio/ui/docs/export/htmx"));
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
