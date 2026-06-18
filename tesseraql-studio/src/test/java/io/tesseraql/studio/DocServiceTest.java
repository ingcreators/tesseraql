package io.tesseraql.studio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.studio.DocService.DocSpec;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.manifest.ManifestLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocServiceTest {

    private static AppManifest exampleManifest() {
        Path appHome = Paths.get("..", "examples", "user-admin-app").toAbsolutePath().normalize();
        return new ManifestLoader().load(appHome);
    }

    @Test
    void liveFallbackGeneratesAReducedModelWhenNoSpecIsPackaged() {
        DocService service = new DocService(exampleManifest());

        assertThat(service.hasPackagedSpec()).isFalse();
        DocSpec spec = service.spec();
        // Live model: routes are present from the manifest, but with no test cross-references.
        assertThat(spec.routes()).anySatisfy(entry -> {
            assertThat(entry.route().id()).isEqualTo("users.search");
            assertThat(entry.tests()).isEmpty();
        });
    }

    @Test
    void routesForTableIndexesReadersAndWritersFromTheBoundSql() {
        DocService service = new DocService(exampleManifest());

        DocService.RouteUsage users = service.routesForTable("users");
        // users.search SELECTs from users; the deactivate/provision routes write to it.
        assertThat(users.readers()).extracting(DocService.RouteRef::id).contains("users.search");
        assertThat(users.writers()).isNotEmpty();
        assertThat(users.readers())
                .allSatisfy(ref -> assertThat(ref.url()).contains("docs/route?id=" + ref.id()));
        // The lookup is case-insensitive; an untouched or null table has an empty usage.
        assertThat(service.routesForTable("USERS").readers())
                .extracting(DocService.RouteRef::id).contains("users.search");
        assertThat(service.routesForTable("no_such_table").isEmpty()).isTrue();
        assertThat(service.routesForTable(null).isEmpty()).isTrue();
    }

    @Test
    void readsThePackagedSpecWithTestCrossReferences(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/spec.json"), """
                {
                  "routes": [
                    { "route": { "id": "users.search", "method": "GET", "path": "/api/users",
                                 "recipe": "query-json", "kind": "route", "inputs": [],
                                 "security": null, "validations": [], "notifications": [],
                                 "response": null, "sql": [] },
                      "tests": [ { "name": "search finds sato", "kind": "sql",
                                   "target": "web/api/users/search.sql" } ] }
                  ],
                  "migrations": [
                    { "datasource": "main", "vendor": null, "version": "1",
                      "description": "init", "path": "db/migration/V1__init.sql" }
                  ]
                }
                """);
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.appName()).isEqualTo("demo");
        assertThat(service.hasPackagedSpec()).isTrue();
        DocSpec spec = service.spec();
        assertThat(spec.routes()).singleElement().satisfies(entry -> {
            assertThat(entry.route().id()).isEqualTo("users.search");
            assertThat(entry.tests()).singleElement().satisfies(test -> {
                assertThat(test.name()).isEqualTo("search finds sato");
                assertThat(test.kind()).isEqualTo("sql");
            });
        });
        assertThat(spec.migrations()).singleElement()
                .satisfies(migration -> assertThat(migration.version()).isEqualTo("1"));
        assertThat(service.route("users.search")).isNotNull();
        assertThat(service.route("nope")).isNull();
    }

    @Test
    void readsTheRunOverlayWhenPresent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/report.json"), """
                {
                  "schemaVersion": 1, "runId": "run-1", "generatedAt": "2026-06-15T12:00:00Z",
                  "summary": { "total": 1, "passed": 1, "failed": 0, "sqlLineRatio": 0.5,
                               "sqlBranchRatio": 1.0, "gatePassed": true },
                  "thresholds": { "sqlLine": 0.0, "sqlBranch": 0.0, "kinds": {} },
                  "gate": { "passed": true, "failures": [] },
                  "kinds": [ { "kind": "route", "ratio": 1.0, "covered": 1, "declared": 1,
                               "uncovered": [] } ],
                  "routes": {
                    "users.search": { "covered": true,
                      "tests": [ { "name": "finds sato", "passed": true, "message": "OK" } ],
                      "sql": [ { "file": "web/api/users/search.sql", "lineRatio": 0.5,
                                 "branchRatio": 1.0, "branchCount": 1, "branchOutcomes": 2,
                                 "coveredLines": [1], "coverableLines": [1, 2] } ],
                      "itemCoverage": { "validation": 1.0 } }
                  }
                }
                """);
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.hasReport()).isTrue();
        ReportOverlay overlay = service.report();
        assertThat(overlay).isNotNull();
        assertThat(overlay.runId()).isEqualTo("run-1");
        assertThat(overlay.summary().passed()).isEqualTo(1);
        assertThat(overlay.routeReport("users.search")).satisfies(route -> {
            assertThat(route.covered()).isTrue();
            assertThat(route.tests()).singleElement()
                    .satisfies(test -> assertThat(test.passed()).isTrue());
            assertThat(route.sql()).singleElement()
                    .satisfies(sql -> assertThat(sql.coverableLines()).containsExactly(1, 2));
        });
    }

    @Test
    void degradesGracefullyWhenTheOverlayIsAbsentOrCorrupt(@TempDir Path dir) throws Exception {
        // Absent overlay (the example app has none): no report, portal still works on the spec.
        DocService noReport = new DocService(exampleManifest());
        assertThat(noReport.hasReport()).isFalse();
        assertThat(noReport.report()).isNull();

        // Corrupt overlay: present but unreadable -> degrade to null rather than break the portal.
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/report.json"), "{ not valid json");
        DocService corrupt = new DocService(new ManifestLoader().load(dir));
        assertThat(corrupt.hasReport()).isTrue();
        assertThat(corrupt.report()).isNull();
    }

    @Test
    void searchScoresByMatchedTermsAndSupportsPrefixMatching() {
        DocService service = new DocService(exampleManifest());

        List<DocService.Hit> hits = service.search("users provision");
        // The provisioning routes surface, and hits come back ordered by descending match score.
        assertThat(hits).extracting(DocService.Hit::id)
                .contains("users.apiProvision", "groups.apiProvision");
        assertThat(hits).isSortedAccordingTo(
                Comparator.comparingInt(DocService.Hit::score).reversed());
        // A prefix matches a path/id token (live-search as the user types).
        assertThat(service.search("provis")).anySatisfy(
                hit -> assertThat(hit.id()).isEqualTo("users.apiProvision"));
        assertThat(service.search("   ")).isEmpty();
    }

    @Test
    void readsTheRunHistoryAndDegradesWhenAbsent(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/history.json"), """
                [ { "runId": "r1", "generatedAt": "t1", "total": 2, "passed": 1, "failed": 1,
                    "sqlLineRatio": 0.5, "sqlBranchRatio": 0.5, "gatePassed": false },
                  { "runId": "r2", "generatedAt": "t2", "total": 2, "passed": 2, "failed": 0,
                    "sqlLineRatio": 1.0, "sqlBranchRatio": 1.0, "gatePassed": true } ]
                """);
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.history()).extracting(DocService.HistoryPoint::runId)
                .containsExactly("r1", "r2");
        // An app with no history.json degrades to an empty list.
        assertThat(new DocService(exampleManifest()).history()).isEmpty();
    }

    @Test
    void searchFiltersByRunStatusAndCoverageFromTheOverlay(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/spec.json"), """
                { "routes": [
                    { "route": { "id": "users.search", "method": "GET", "path": "/api/users",
                                 "recipe": "query-json", "kind": "route", "inputs": [],
                                 "validations": [], "notifications": [], "sql": [] }, "tests": [] },
                    { "route": { "id": "users.print", "method": "GET", "path": "/api/users/print",
                                 "recipe": "query-json", "kind": "route", "inputs": [],
                                 "validations": [], "notifications": [], "sql": [] }, "tests": [] }
                  ], "migrations": [] }
                """);
        Files.writeString(dir.resolve(".tesseraql/docs/report.json"), """
                { "schemaVersion": 1, "runId": "r", "generatedAt": "t",
                  "summary": { "total": 2, "passed": 1, "failed": 1, "sqlLineRatio": 1.0,
                               "sqlBranchRatio": 1.0, "gatePassed": false },
                  "thresholds": { "sqlLine": 0.0, "sqlBranch": 0.0, "kinds": {} },
                  "gate": { "passed": false, "failures": [] }, "kinds": [],
                  "routes": {
                    "users.search": { "covered": true,
                      "tests": [ { "name": "a", "passed": true, "message": "OK" } ],
                      "sql": [], "itemCoverage": {} },
                    "users.print": { "covered": false,
                      "tests": [ { "name": "b", "passed": false, "message": "boom" } ],
                      "sql": [], "itemCoverage": {} }
                  } }
                """);
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.search("coverage:covered")).extracting(DocService.Hit::id)
                .containsExactly("users.search");
        assertThat(service.search("coverage:untested")).extracting(DocService.Hit::id)
                .containsExactly("users.print");
        assertThat(service.search("status:failing")).extracting(DocService.Hit::id)
                .containsExactly("users.print");
        assertThat(service.search("status:passing")).extracting(DocService.Hit::id)
                .containsExactly("users.search");
        // A free-text term combines with a filter (matches both, then narrows to the failing one).
        assertThat(service.search("users")).hasSize(2);
        assertThat(service.search("users status:failing")).extracting(DocService.Hit::id)
                .containsExactly("users.print");
    }

    @Test
    void rendersMarkdownDocsAndRejectsTraversal(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve("docs"));
        Files.writeString(dir.resolve("docs/intro.md"), "# Intro\n\nHello.\n");
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.markdown("docs/intro.md")).contains("<h1>Intro</h1>").contains("Hello.");
        assertThatThrownBy(() -> service.markdown("../../etc/passwd"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("escapes app home");
    }

    private static final String SCHEMA_JSON = """
            { "schemaVersion": 1, "generatedAt": "2026-06-15T12:00:00Z",
              "datasources": { "main": { "tables": [
                { "name": "customers", "type": "TABLE", "schema": "public",
                  "columns": [
                    { "name": "id", "jdbcType": -5, "sqlTypeName": "bigserial", "size": 19,
                      "nullable": false, "autoincrement": true, "defaultValue": null },
                    { "name": "email", "jdbcType": 12, "sqlTypeName": "varchar", "size": 320,
                      "nullable": false, "autoincrement": false, "defaultValue": null } ],
                  "primaryKey": ["id"], "foreignKeys": [],
                  "uniqueIndexes": [ { "name": "customers_email_key", "columns": ["email"],
                                      "unique": true } ] },
                { "name": "orders", "type": "TABLE", "schema": "public",
                  "columns": [
                    { "name": "id", "jdbcType": -5, "sqlTypeName": "bigserial", "size": 19,
                      "nullable": false, "autoincrement": true, "defaultValue": null },
                    { "name": "customer_id", "jdbcType": -5, "sqlTypeName": "bigint", "size": 19,
                      "nullable": false, "autoincrement": false, "defaultValue": null } ],
                  "primaryKey": ["id"],
                  "foreignKeys": [ { "name": "orders_customer_id_fkey", "columns": ["customer_id"],
                                     "refTable": "customers", "refColumns": ["id"] } ],
                  "uniqueIndexes": [] }
              ] } } }
            """;

    @Test
    void readsTheSchemaOverlayAndLooksUpTables(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/schema.json"), SCHEMA_JSON);
        DocService service = new DocService(new ManifestLoader().load(dir));

        assertThat(service.hasSchema()).isTrue();
        SchemaOverlay schema = service.schema();
        assertThat(schema).isNotNull();
        assertThat(schema.datasources()).containsKey("main");
        assertThat(service.table("main", "orders")).isNotNull().satisfies(table -> assertThat(
                table.foreignKeys()).singleElement()
                .satisfies(fk -> assertThat(fk.refTable()).isEqualTo("customers")));
        assertThat(service.table("main", "nope")).isNull();
        assertThat(service.table("other", "orders")).isNull();
    }

    @Test
    void degradesGracefullyWhenSchemaIsAbsentOrCorrupt(@TempDir Path dir) throws Exception {
        // Absent schema (the example app has none): no overlay, portal still works on the spec.
        DocService noSchema = new DocService(exampleManifest());
        assertThat(noSchema.hasSchema()).isFalse();
        assertThat(noSchema.schema()).isNull();
        assertThat(noSchema.table("main", "x")).isNull();

        // Corrupt schema: present but unreadable -> degrade to null rather than break the portal.
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/schema.json"), "{ not valid json");
        DocService corrupt = new DocService(new ManifestLoader().load(dir));
        assertThat(corrupt.hasSchema()).isTrue();
        assertThat(corrupt.schema()).isNull();
    }

    @Test
    void searchSurfacesSchemaTablesAndColumns(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: demo\n");
        Files.createDirectories(dir.resolve(".tesseraql/docs"));
        Files.writeString(dir.resolve(".tesseraql/docs/spec.json"),
                "{ \"routes\": [], \"migrations\": [] }");
        Files.writeString(dir.resolve(".tesseraql/docs/schema.json"), SCHEMA_JSON);
        DocService service = new DocService(new ManifestLoader().load(dir));

        // A table name resolves to a table hit linking to the table page.
        assertThat(service.search("customers")).anySatisfy(hit -> {
            assertThat(hit.method()).isEqualTo("TABLE");
            assertThat(hit.url()).contains("schema/table?ds=main&name=customers");
        });
        // A column name surfaces the table that declares it.
        assertThat(service.search("customer_id")).extracting(DocService.Hit::id)
                .contains("orders");
        // Status/coverage filters are route-only: table hits never satisfy them.
        assertThat(service.search("coverage:untested"))
                .noneMatch(hit -> "TABLE".equals(hit.method()));
    }

    @Test
    void exportsTheOpenApiDocumentGeneratedLiveFromTheManifest() throws Exception {
        DocService service = new DocService(exampleManifest());

        com.fasterxml.jackson.databind.JsonNode doc = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(service.openApiJson());
        // A real OpenAPI 3 document: the version marker, the app's title, and its routes.
        assertThat(doc.path("openapi").asText()).isEqualTo("3.0.3");
        assertThat(doc.path("info").path("title").asText()).isEqualTo("user-admin");
        assertThat(doc.path("paths").fieldNames()).toIterable().contains("/api/users");
        assertThat(doc.path("components").path("securitySchemes").has("bearerAuth")).isTrue();
    }

    @Test
    void exportsTheHtmxContractGeneratedLiveFromTheManifest() throws Exception {
        DocService service = new DocService(exampleManifest());

        // Well-formed JSON object; its detailed shape is the generator's own contract (tested there).
        com.fasterxml.jackson.databind.JsonNode doc = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(service.htmxContractJson());
        assertThat(doc.isObject()).isTrue();
    }

    @Test
    void routeCatalogProjectsOneFlatRowPerRouteForThePrintableExport() {
        DocService service = new DocService(exampleManifest());

        List<java.util.Map<String, Object>> rows = service.routeCatalog();
        assertThat(rows).isNotEmpty();
        // Each row is a flat projection keyed for the PDF columns; the search route is present.
        assertThat(rows).anySatisfy(row -> {
            assertThat(row).containsEntry("id", "users.search").containsEntry("method", "GET")
                    .containsEntry("path", "/api/users").containsEntry("recipe", "query-json");
            assertThat(row).containsKey("tests");
        });
    }
}
