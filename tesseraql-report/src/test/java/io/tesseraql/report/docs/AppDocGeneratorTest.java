package io.tesseraql.report.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.tesseraql.report.docs.DocModel.RouteDoc;
import io.tesseraql.report.docs.DocModel.TestCaseDoc;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class AppDocGeneratorTest {

    private static final AppManifest MANIFEST = manifest();
    private static final DocModel MODEL = new AppDocGenerator().generate(MANIFEST);

    private static AppManifest manifest() {
        // Tests run from the module directory; the example app lives at the repo root.
        Path app = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(app);
    }

    private static RouteDoc route(String id) {
        return MODEL.routes().stream().filter(doc -> id.equals(doc.route().id())).findFirst()
                .orElseThrow(() -> new AssertionError("no route doc for " + id));
    }

    @Test
    void attachesSqlExercisingTestsToTheRouteTheyCover() {
        RouteDoc search = route("users.search");

        assertThat(search.tests())
                .extracting(TestCaseDoc::name, TestCaseDoc::kind, TestCaseDoc::target)
                .containsExactly(
                        tuple("search finds sato by name", "sql", "web/api/users/search.sql"),
                        tuple("search without query returns all users", "sql",
                                "web/api/users/search.sql"),
                        // The write case's verify: read-back runs search.sql, so it links too.
                        tuple("deactivating sato affects one row and the search sees the new status",
                                "sql", "web/api/users/deactivate/deactivate.sql"));
    }

    @Test
    void attachesValidateAndNotifyTargetedTestsByRouteId() {
        RouteDoc provision = route("users.apiProvision");

        assertThat(provision.tests())
                .extracting(TestCaseDoc::kind, TestCaseDoc::target)
                .containsExactly(
                        tuple("validation", "users.apiProvision"),
                        tuple("validation", "users.apiProvision"),
                        tuple("notification", "users.apiProvision"),
                        tuple("notification", "users.apiProvision"));
    }

    @Test
    void carriesThroughTheRouteSpecAndMigrationListing() {
        RouteDoc search = route("users.search");
        // The wrapped RouteSpec is present with its bound SQL.
        assertThat(search.route().recipe()).isEqualTo("query-json");
        assertThat(search.route().sql()).singleElement()
                .satisfies(statement -> assertThat(statement.binds())
                        .containsExactly("q", "limit", "offset"));
        // user-admin-app owns its schema via db/migration/V1__create_users.sql.
        assertThat(MODEL.migrations()).singleElement().satisfies(migration -> {
            assertThat(migration.datasource()).isEqualTo("main");
            assertThat(migration.version()).isEqualTo("1");
            assertThat(migration.description()).isEqualTo("create_users");
        });
    }

    @Test
    void serializesByteStableJson() {
        AppDocGenerator generator = new AppDocGenerator();

        String first = generator.toJson(MANIFEST);
        String second = generator.toJson(MANIFEST);

        assertThat(first).isEqualTo(second);
        assertThat(first).contains("\"users.search\"")
                .contains("\"search finds sato by name\"");
    }
}
