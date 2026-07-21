package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lints the duckdb datasource surface (docs/duckdb.md): TQL-YAML-1040 and TQL-SQL-2111. */
class AppLinterDuckDbTest {

    private static void writeConfig(Path dir, String analyticsBlock) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                %s""".formatted(analyticsBlock));
    }

    private static void writeAnalyticsRoute(Path dir, String sql) throws Exception {
        Path routeDir = dir.resolve("web/api/sales");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("summary.sql"), sql);
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: sales.summary
                kind: route
                recipe: query-json
                datasource: analytics
                sql:
                  file: summary.sql
                  mode: query
                """);
    }

    private static final String SCOPES = """
                  duckdb:
                    fileScopes:
                      sales:
                        root: data/sales
            """;

    @Test
    void acceptsAScopedReadOnADuckDbDatasource(@TempDir Path dir) throws Exception {
        writeConfig(dir, SCOPES);
        writeAnalyticsRoute(dir,
                "select * from read_parquet(/* ${scope.sales}/monthly.parquet */ 'd.parquet')\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).noneMatch(LintFinding::isError);
    }

    @Test
    void flagsAnUndeclaredScopeARawArgumentAndADatasetPlaceholder(@TempDir Path dir)
            throws Exception {
        writeConfig(dir, SCOPES);
        writeAnalyticsRoute(dir, """
                select * from read_parquet(/* ${scope.ghost}/m.parquet */ 'd.parquet')
                union all
                select * from read_csv('raw.csv')
                union all
                select * from read_parquet(/* ${dataset.report} */ 'd.parquet')
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2111") && f.isError()
                && f.message().contains("File scope 'ghost'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2111") && f.isError()
                && f.message().contains("raw argument"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2111") && f.isError()
                && f.message().contains("dataset catalog"));
    }

    @Test
    void flagsAFilePlaceholderOffTheDuckDbDatasource(@TempDir Path dir) throws Exception {
        writeConfig(dir, SCOPES);
        Path routeDir = dir.resolve("web/api/orders");
        Files.createDirectories(routeDir);
        Files.writeString(routeDir.resolve("orders.sql"),
                "select * from read_parquet(/* ${scope.sales}/m.parquet */ 'd.parquet')\n");
        Files.writeString(routeDir.resolve("get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                sql:
                  file: orders.sql
                  mode: query
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2111") && f.isError()
                && f.message().contains("only resolves on a duckdb datasource"));
    }

    @Test
    void flagsStructuralConstraints(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: "jdbc:duckdb:"
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        fileScopes:
                          bad:
                            root: ../outside
                            partitionBy: user
                """);
        Files.createDirectories(dir.resolve("db/analytics/migration"));
        Files.writeString(dir.resolve("db/analytics/migration/V1__init.sql"), "select 1\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("main cannot be a duckdb datasource"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("without '..'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("partitionBy must be 'tenant'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("nothing durable to migrate"));
    }

    @Test
    void flagsAttachMisdeclarations(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  datasources:
                    main:
                      jdbcUrl: jdbc:postgresql://localhost/main
                    analytics:
                      jdbcUrl: "jdbc:duckdb:"
                      duckdb:
                        extensions: [postgres, "bad name"]
                        attach:
                          - { datasource: main }
                          - { datasource: ghost, as: g }
                          - { datasource: analytics, as: self }
                          - { datasource: main, as: app, mode: sometimes }
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("'bad name'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("other than 'main'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1035") && f.isError()
                && f.message().contains("'ghost'"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("itself a duckdb datasource"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("mode must be readonly or readwrite"));
    }

    @Test
    void appliesTheSqlRulesToBatchJobs(@TempDir Path dir) throws Exception {
        writeConfig(dir, SCOPES);
        Path job = dir.resolve("batch/sales");
        Files.createDirectories(job);
        Files.writeString(job.resolve("load.yml"), """
                version: tesseraql/v1
                id: sales.load
                kind: job
                recipe: batch-tasklet
                datasource: analytics
                trigger:
                  schedule:
                    cron: "0 0 4 * * ?"
                sql:
                  file: load.sql
                  mode: update
                """);
        Files.writeString(job.resolve("load.sql"),
                "insert into t select * from read_parquet(/* ${scope.ghost}/m.parquet */ 'd')\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-SQL-2111") && f.isError()
                && f.message().contains("File scope 'ghost'"));
    }

    @Test
    void flagsTransactionalRecipesAndProjectionTargets(@TempDir Path dir) throws Exception {
        writeConfig(dir, SCOPES);
        Path commandDir = dir.resolve("web/api/load");
        Files.createDirectories(commandDir);
        Files.writeString(commandDir.resolve("load.sql"), "select 1\n");
        Files.writeString(commandDir.resolve("post.yml"), """
                version: tesseraql/v1
                id: load.run
                kind: route
                recipe: command-json
                datasource: analytics
                sql:
                  file: load.sql
                  mode: update
                response:
                  json:
                    status: 201
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1040") && f.isError()
                && f.message().contains("serves reads"));
    }
}
