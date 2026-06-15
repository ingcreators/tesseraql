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
}
