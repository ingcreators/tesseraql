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

        Map<String, Object> model = DocViews.index("demo", spec);

        assertThat(model).containsEntry("appName", "demo").containsEntry("hasRoutes", true)
                .containsEntry("hasMigrations", true);
        assertThat(asRows(model.get("routes"))).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("id", "users.search").containsEntry("testCount", 1);
            assertThat((String) row.get("detailUrl")).contains("docs/route?id=users.search");
        });
        assertThat(asRows(model.get("migrations"))).singleElement()
                .satisfies(row -> assertThat(row).containsEntry("version", "1"));
    }

    @Test
    void routeModelProjectsInputsSecuritySqlStructureAndTests() {
        Map<String, Object> model = DocViews.route(searchRoute());

        assertThat(model).containsEntry("id", "users.search").containsEntry("hasSql", true)
                .containsEntry("hasTests", true);
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
