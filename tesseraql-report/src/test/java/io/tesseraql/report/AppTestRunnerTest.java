package io.tesseraql.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.jdbc.DriverManagerDataSource;
import io.tesseraql.coverage.CoverageGate;
import io.tesseraql.coverage.CoverageThresholds;
import io.tesseraql.identity.RealmConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the runner judges before a case runs (docs/audit-low-leads.md slice 12): the SQL population
 * it is accountable for (G16) and the case names the reports join on (G19). No database is
 * reached — the datasource below would refuse a connection, and none is asked for.
 */
class AppTestRunnerTest {

    private static final DataSource UNREACHED = new DriverManagerDataSource(
            "jdbc:postgresql://127.0.0.1:1/never", "x", "x");

    private static Path app(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/api/items"));
        Files.writeString(dir.resolve("web/api/items/search.sql"), """
                select * from items where 1 = 1
                /*%if q != null */ and name = /* q */'x' /*%end*/
                """);
        Files.writeString(dir.resolve("web/api/items/get.yml"), """
                version: tesseraql/v1
                id: items.search
                kind: route
                recipe: query-json
                input:
                  q:
                    type: string
                sources:
                  main:
                    sql:
                      file: search.sql
                      params:
                        q: query.q
                response:
                  json:
                    body:
                      data: main.rows
                """);
        Files.createDirectories(dir.resolve("web/api/items/count"));
        Files.writeString(dir.resolve("web/api/items/count/count.sql"),
                "select count(*) as n\nfrom items\n");
        Files.writeString(dir.resolve("web/api/items/count/get.yml"), """
                version: tesseraql/v1
                id: items.count
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: count.sql
                response:
                  json:
                    body:
                      data: main.rows
                """);
        return dir;
    }

    @Test
    void everyBoundSqlFileIsDeclaredBeforeTheFirstCaseRuns(@TempDir Path dir) throws Exception {
        // No suite at all: the gate used to pass an 80/80 threshold with "sql": {} and the trend
        // recorded 100% — the population was the files a case rendered.
        Path app = app(dir);

        AppTestRunner.RunResult result = new AppTestRunner().run(app, UNREACHED,
                RealmConfig.managed("local", "main"), dir.resolve("reports"));

        assertThat(result.report().results()).isEmpty();
        assertThat(result.coverage().reports()).containsOnlyKeys("web/api/items/search.sql",
                "web/api/items/count/count.sql");
        assertThat(result.coverage().report("web/api/items/search.sql").lineRatio()).isZero();
        assertThat(result.coverage().report("web/api/items/search.sql").branchRatio()).isZero();
        assertThat(result.coverage().report("web/api/items/count/count.sql").lineRatio()).isZero();
        CoverageGate.Result gate = CoverageGate.check(result.coverage(), result.kinds(),
                CoverageThresholds.ofPercent(80, 80));
        assertThat(gate.passed()).isFalse();
        assertThat(gate.violations()).anyMatch(v -> v.startsWith("web/api/items/count/count.sql:"
                + " line coverage 0%"));
        assertThat(Files.readString(dir.resolve("reports/coverage/sql-coverage.json")))
                .contains("\"web/api/items/count/count.sql\"");
    }

    @Test
    void aCaseNameDeclaredTwiceAcrossSuitesIsRefused(@TempDir Path dir) throws Exception {
        // The reports join results to cases by name: both twins showed the FIRST result, so a
        // failing duplicate rendered green on the portal's route page.
        Path app = app(dir);
        Files.createDirectories(app.resolve("tests"));
        Files.writeString(app.resolve("tests/a.yml"), """
                version: tesseraql/v1
                tests:
                  - name: duplicated name
                    sql:
                      file: web/api/items/count/count.sql
                    expect:
                      rowCount: 1
                """);
        Files.writeString(app.resolve("tests/b.yml"), """
                version: tesseraql/v1
                tests:
                  - name: duplicated name
                    sql:
                      file: web/api/items/count/count.sql
                    expect:
                      rowCount: 77
                """);

        assertThatThrownBy(() -> new AppTestRunner().run(app, UNREACHED,
                RealmConfig.managed("local", "main"), dir.resolve("reports")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1410")
                .hasMessageContaining("'duplicated name' is declared twice")
                .hasMessageContaining("tests/a.yml").hasMessageContaining("tests/b.yml");
    }

    @Test
    void aCaseFilterThatNamesNoCaseIsRefusedBeforeAnythingIsWritten(@TempDir Path dir)
            throws Exception {
        // A --case pinned to a since-renamed case used to run nothing, exit 0, and with --report
        // overwrite the overlay with an all-green run of nothing.
        Path app = app(dir);
        Files.createDirectories(app.resolve("tests"));
        Files.writeString(app.resolve("tests/a.yml"), """
                version: tesseraql/v1
                tests:
                  - name: control case
                    sql:
                      file: web/api/items/count/count.sql
                    expect:
                      rowCount: 1
                """);

        assertThatThrownBy(() -> new AppTestRunner().run(app, UNREACHED,
                RealmConfig.managed("local", "main"), dir.resolve("reports"),
                Set.of("control  case")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-YAML-1411")
                .hasMessageContaining("No test case is named [control  case]");
        assertThat(dir.resolve("reports")).doesNotExist();
    }
}
