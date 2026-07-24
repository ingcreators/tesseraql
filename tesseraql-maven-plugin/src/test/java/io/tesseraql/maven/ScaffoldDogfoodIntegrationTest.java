package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.apptasks.AppMigrator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import io.tesseraql.yaml.scaffold.AppScaffolder;
import io.tesseraql.yaml.scaffold.CrudScaffolder;
import io.tesseraql.yaml.scaffold.ScaffoldedFile;
import io.tesseraql.yaml.scaffold.TableIntrospector;
import io.tesseraql.yaml.scaffold.TableSchema;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Dogfoods the Phase 23 generators against the example gallery: the committed
 * {@code examples/scaffold-demo-app} must be byte-for-byte what {@code tesseraql new} plus
 * {@code tesseraql scaffold crud --table items} produce from the skeleton's own migration applied
 * to PostgreSQL — proving generation is deterministic (ch. 48) — and the generated app must lint
 * cleanly and pass its own declarative suites with full branch coverage of the generated SQL.
 *
 * <p>When the generators change intentionally, regenerate the gallery with
 * {@code mvn -pl tesseraql-maven-plugin test -Dtest=ScaffoldDogfoodIntegrationTest
 * -Dtesseraql.scaffold.regenerate=true} and commit the diff.
 */
@Testcontainers
class ScaffoldDogfoodIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Path EXAMPLE = Path.of("..", "examples", "scaffold-demo-app")
            .toAbsolutePath().normalize();

    static DataSource dataSource;
    static Map<String, String> generated;

    @BeforeAll
    static void generateFromTheSkeletonSchema(@TempDir Path workDir) throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        dataSource = ds;

        AppScaffolder apps = new AppScaffolder();
        List<ScaffoldedFile> skeleton = apps.scaffold("scaffold-demo");
        Path bootstrap = workDir.resolve("scaffold-demo");
        apps.writeNew(bootstrap, skeleton);
        AppMigrator.migrate(bootstrap, "scaffold-demo", "main", dataSource).orElseThrow();

        TableSchema schema;
        try (Connection connection = dataSource.getConnection()) {
            schema = new TableIntrospector().introspect(connection, "items");
        }
        // Mirror the CLI: the CRUD scaffolder defers to the skeleton's declared security
        // defaults, so the gallery carries the slim security blocks.
        io.tesseraql.yaml.config.AppConfig config = new io.tesseraql.yaml.manifest.ManifestLoader()
                .load(bootstrap).config();
        List<ScaffoldedFile> crud = new CrudScaffolder(
                io.tesseraql.yaml.config.SecurityDefaults.from(config)).scaffold(schema);

        Map<String, String> files = new LinkedHashMap<>();
        skeleton.forEach(file -> files.put(file.path(), file.content()));
        crud.forEach(file -> files.put(file.path(), file.stampedContent()));
        generated = files;
    }

    @Test
    void galleryAppIsExactlyTheGeneratorOutput() throws Exception {
        if (Boolean.getBoolean("tesseraql.scaffold.regenerate")) {
            regenerate();
            return;
        }
        Map<String, String> committed = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(EXAMPLE)) {
            walk.filter(Files::isRegularFile).forEach(file -> committed.put(
                    EXAMPLE.relativize(file).toString().replace('\\', '/'), read(file)));
        }

        assertThat(committed.keySet())
                .as("gallery file set (regenerate with -Dtesseraql.scaffold.regenerate=true)")
                .containsExactlyInAnyOrderElementsOf(generated.keySet());
        // The scaffolded wrapper POM pins the building framework version (TesseraqlVersion), which
        // differs between a SNAPSHOT dev build and a release build; normalize it so the gallery
        // comparison stays version-independent (no churn at release time).
        generated.forEach((path, content) -> assertThat(normalizeVersion(committed.get(path)))
                .as(path).isEqualTo(normalizeVersion(content)));
    }

    private static String normalizeVersion(String content) {
        return content.replaceAll("<tesseraql\\.version>[^<]+</tesseraql\\.version>",
                "<tesseraql.version>VERSION</tesseraql.version>");
    }

    @Test
    void galleryAppLintsCleanlyAndItsSuitesPass(@TempDir Path reportDir) {
        List<LintFinding> findings = new AppLinter().lint(EXAMPLE);
        assertThat(findings).noneMatch(LintFinding::isError);
        // The skeleton defines the app.read/app.write policies the scaffolds reference.
        assertThat(findings).noneMatch(finding -> finding.code().equals("TQL-SEC-4030"));

        AppTestRunner.RunResult result = new AppTestRunner()
                .run(EXAMPLE, dataSource, RealmConfig.managed("local", "main"), reportDir);

        // Two skeleton smoke cases + the crud suite (no-filter, filter, a descending sort, detail).
        // The sort is an embedded variable (no branches), so it needs no per-column case.
        assertThat(result.report().results()).hasSize(6);
        assertThat(result.report().allPassed())
                .as(() -> result.report().results().toString()).isTrue();
        // Every branch of both generated search templates is exercised — for the crud fragment the
        // only branch is the q filter (the embedded ORDER BY adds none).
        assertThat(result.coverage().report("web/api/items/search.sql").branchRatio())
                .isEqualTo(1.0);
        assertThat(result.coverage().report("web/items/search.sql")
                .branchRatio()).isEqualTo(1.0);
        // The suites prove the SQL-bound generated routes — the list is one view-backed route
        // now (no fragment route), so it also counts into the view coverage kind.
        assertThat(result.kind("route").covered())
                .contains("items.search", "items.page", "items.detail");
        assertThat(result.kind("security").covered())
                .contains("items.search", "items.page", "items.detail");
        assertThat(result.kind("view").declared())
                .contains("items.page", "items.detail", "items.new");
    }

    /** Rewrites the gallery from the current generators (then commit the diff). */
    private static void regenerate() throws IOException {
        try (Stream<Path> walk = Files.walk(EXAMPLE)) {
            walk.filter(Files::isRegularFile)
                    .filter(file -> !generated.containsKey(
                            EXAMPLE.relativize(file).toString().replace('\\', '/')))
                    .forEach(stale -> {
                        try {
                            Files.delete(stale);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
        }
        for (Map.Entry<String, String> file : generated.entrySet()) {
            Path target = EXAMPLE.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        System.out.println("Regenerated " + generated.size() + " gallery files under "
                + EXAMPLE + "; review and commit the diff.");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
