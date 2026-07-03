package io.tesseraql.maven;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.apptasks.AppMigrator;
import io.tesseraql.identity.RealmConfig;
import io.tesseraql.report.AppTestRunner;
import io.tesseraql.test.TestReport;
import io.tesseraql.yaml.governance.AdmissionProfile;
import io.tesseraql.yaml.lint.AppLinter;
import io.tesseraql.yaml.lint.LintFinding;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The template gallery bar (roadmap Phase 47): every starter app under {@code examples/}
 * that belongs to the gallery lints clean, passes its own declarative test suites against a
 * real database, and clears the marketplace admission profile — we ship only what we would
 * admit from anyone else.
 */
@Testcontainers
class GalleryAppsIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @ParameterizedTest
    @ValueSource(strings = {"purchase-request-app", "inventory-app", "helpdesk-app"})
    void galleryAppLintsCleanPassesItsSuitesAndClearsAdmission(String appName)
            throws Exception {
        Path example = Paths.get("..", "examples", appName).toAbsolutePath().normalize();

        // Lint clean: not a single error.
        assertThat(new AppLinter().lint(example).stream().filter(LintFinding::isError))
                .as(appName + " lint errors").isEmpty();

        // The marketplace admission profile passes (docs/admission.md).
        AdmissionProfile.Report admission = AdmissionProfile.check(example);
        assertThat(admission.failures()).as(appName + " admission failures").isEmpty();

        // The app's own declarative suites pass against a real schema. Each app gets a
        // scratch database so gallery apps can never interfere with each other.
        String database = appName.replace('-', '_');
        try (var connection = dataSource("postgres").getConnection();
                var statement = connection.createStatement()) {
            statement.execute("drop database if exists " + database);
            statement.execute("create database " + database);
        }
        DataSource appData = dataSource(database);

        // Suites run against a copy: the runner writes work/ artifacts we keep out of git.
        Path workDir = Files.createTempDirectory("tesseraql-gallery-" + appName);
        try (Stream<Path> files = Files.walk(example)) {
            files.forEach(path -> {
                try {
                    Path target = workDir.resolve(example.relativize(path).toString());
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(path, target);
                    }
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            });
        }
        Path reportDir = Files.createTempDirectory("tesseraql-gallery-report");
        try {
            AppMigrator.migrate(workDir, appName, "main", appData).orElseThrow();
            TestReport report = new AppTestRunner()
                    .run(workDir, appData, RealmConfig.managed("local", "main"), reportDir)
                    .report();
            assertThat(report.results()).as(appName + " suite results").isNotEmpty();
            assertThat(report.results())
                    .as(appName + " failing cases: " + report.results())
                    .allMatch(TestReport.TestResult::passed);
        } finally {
            try (Stream<Path> files = Files.walk(workDir)) {
                files.sorted(Comparator.reverseOrder())
                        .forEach(path -> path.toFile().delete());
            }
        }
    }

    private static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replaceAll("/[^/]+$", "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
