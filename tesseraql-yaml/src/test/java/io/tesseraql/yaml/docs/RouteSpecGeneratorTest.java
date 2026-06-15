package io.tesseraql.yaml.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class RouteSpecGeneratorTest {

    private static final RouteSpecModel MODEL = new RouteSpecGenerator().generate(manifest());

    private static AppManifest manifest() {
        // Tests run from the module directory; the example app lives at the repo root.
        Path app = Paths.get("..", "examples", "scaffold-demo-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(app);
    }

    private static RouteSpec route(String id) {
        return MODEL.routes().stream().filter(spec -> id.equals(spec.id())).findFirst()
                .orElseThrow(() -> new AssertionError("no route spec for " + id));
    }

    @Test
    void routesAreOrderedByPathThenMethod() {
        assertThat(MODEL.routes()).extracting(RouteSpec::method, RouteSpec::path)
                .containsExactly(
                        tuple("GET", "/"),
                        tuple("GET", "/api/items"),
                        tuple("GET", "/items"),
                        tuple("POST", "/items/create"),
                        tuple("GET", "/items/fragments/table"),
                        tuple("GET", "/items/new"),
                        tuple("GET", "/items/{id}"),
                        tuple("POST", "/items/{id}/delete"),
                        tuple("POST", "/items/{id}/update"));
    }

    @Test
    void projectsTheQueryRouteSurfaceSecurityAndResponse() {
        RouteSpec search = route("items.search");

        assertThat(search.method()).isEqualTo("GET");
        assertThat(search.path()).isEqualTo("/api/items");
        assertThat(search.recipe()).isEqualTo("query-json");
        // Inputs are sorted by name (RouteDefinition.input() is unordered).
        assertThat(search.inputs())
                .extracting(RouteSpec.Input::name, RouteSpec.Input::type,
                        RouteSpec.Input::required, RouteSpec.Input::min, RouteSpec.Input::max,
                        RouteSpec.Input::maxLength)
                .containsExactly(
                        tuple("limit", "integer", false, 1, 200, null),
                        tuple("offset", "integer", false, 0, null, null),
                        tuple("q", "string", false, null, null, 200));
        assertThat(search.security())
                .isEqualTo(new RouteSpec.Security("bearer", "app.read", null, false));
        assertThat(search.response())
                .isEqualTo(new RouteSpec.Response("json", 200, null, null, null));
    }

    @Test
    void projectsBoundSqlWithDeclaredBindsAndIfStructure() {
        RouteSpec search = route("items.search");

        assertThat(search.sql()).singleElement().satisfies(statement -> {
            assertThat(statement.label()).isEqualTo("sql");
            assertThat(statement.file()).isEqualTo("search.sql");
            assertThat(statement.contract()).isNull();
            assertThat(statement.mode()).isEqualTo("query");
            assertThat(statement.statement()).contains("select").contains("from\n  items i");
            // Distinct binds in first-seen order: q is inside the if-block, then limit and offset.
            assertThat(statement.binds()).containsExactly("q", "limit", "offset");
            assertThat(statement.structure()).containsExactly(
                    new RouteSpec.Control("if", "q != null && q != \"\"", 0));
        });
    }

    @Test
    void projectsCommandRouteSecurityStepsAndRedirect() {
        RouteSpec create = route("items.create");

        assertThat(create.recipe()).isEqualTo("command-json");
        assertThat(create.security())
                .isEqualTo(new RouteSpec.Security("browser", "app.write", null, true));
        assertThat(create.response())
                .isEqualTo(new RouteSpec.Response("redirect", 303, null, null,
                        "/items/{steps.record.keys.id}"));
        // No main sql; the write is a named step whose insert binds audit columns.
        assertThat(create.sql()).singleElement().satisfies(statement -> {
            assertThat(statement.label()).isEqualTo("step:record");
            assertThat(statement.file()).isEqualTo("insert.sql");
            assertThat(statement.mode()).isEqualTo("update");
            assertThat(statement.binds())
                    .contains("name", "quantity", "audit.user", "audit.now");
        });
    }

    @Test
    void listsTheMigrationWithAnAppHomeRelativePath() {
        assertThat(MODEL.migrations()).containsExactly(
                new RouteSpecModel.Migration("main", null, "1", "create_items",
                        "db/migration/V1__create_items.sql"));
    }
}
