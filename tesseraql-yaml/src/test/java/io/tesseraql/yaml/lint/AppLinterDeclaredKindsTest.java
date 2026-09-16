package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The declared-kind lints (docs/temporal-semantics.md T3, {@code TQL-YAML-1064}), and the
 * domain reference a {@code result:} entry makes.
 */
class AppLinterDeclaredKindsTest {

    private static final String KIND = "TQL-YAML-1064";

    private Path app(@TempDir Path dir, String input, String result) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("domains"));
        Files.writeString(dir.resolve("domains/fields.yml"), """
                version: tesseraql/v1
                domains:
                  order_date:
                    type: date
                    format: yyyy/MM/dd
                    maxLength: 10
                  payload:
                    type: json
                  eur_amount:
                    type: number
                    format: "#,##0.00"
                    locale: de-DE
                """);
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-json
                security:
                  auth: public
                %s
                sources:
                  main:
                    sql:
                      file: orders.sql
                %s
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(input, result));
        Files.writeString(dir.resolve("web/orders/orders.sql"),
                "select ordered_on, payload from orders\n");
        return dir;
    }

    @Test
    void aJsonInputIsAnErrorNamingTheField(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "input:\n  payload: { domain: payload }", "    result: {}"));

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.source().equals("web/orders/get.yml")
                && f.message()
                        .contains("input.payload.type: 'json' is not a type a request binds"));
    }

    @Test
    void anUnknownInputTypeIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "input:\n  qty: { type: integr }", "    result: {}"));

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("'integr' is not a type a request binds"));
    }

    @Test
    void aResultEntryWithAKindNoReadParsesIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "    result:\n      ordered_on: { type: string }"));

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("sources.main.result.ordered_on.type: 'string' is not a"
                        + " kind a result: declaration parses"));
    }

    @Test
    void aResultEntryWithABadFormatIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "    result:\n      ordered_on: { type: date, format: \"yyyy-bb\" }"));

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("sources.main.result.ordered_on.format:")
                && f.message().contains("is not a DateTimeFormatter pattern"));
    }

    @Test
    void aWellFormedDeclarationLintsCleanAndCountsAsADomainReference(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "    result:\n      ordered_on: { domain: order_date }\n"
                        + "      payload: { domain: payload }"));

        assertThat(findings).noneMatch(f -> f.code().equals(KIND));
        // Both domains are referenced from result: only; before T3 that was "never referenced".
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-4611")
                && (f.message().contains("'order_date'") || f.message().contains("'payload'")));
    }

    @Test
    void aRestatedFormatOverTheDomainsIsNotAFinding(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "    result:\n      ordered_on: { domain: order_date, format: yyyyMMdd }\n"
                        + "      payload: { domain: payload }"));

        assertThat(findings).noneMatch(f -> f.code().equals(KIND)
                || f.code().equals("TQL-FIELD-4610"));
    }

    /**
     * Decision 24: the domain's constraint key is not applied on read and is not a finding;
     * the same key written on the entry is refused — exactly, because the loader merges a
     * domain into a result: entry by the read keys alone.
     */
    @Test
    void aConstraintKeyWrittenOnAResultEntryIsAnErrorWhileTheDomainsIsNot(@TempDir Path dir)
            throws Exception {
        List<LintFinding> clean = new AppLinter().lint(app(dir.resolve("clean"), "",
                "    result:\n      ordered_on: { domain: order_date }"));
        assertThat(clean).noneMatch(f -> f.code().equals(KIND));

        List<LintFinding> findings = new AppLinter().lint(app(dir.resolve("written"), "",
                "    result:\n      ordered_on: { domain: order_date, maxLength: 10 }"));
        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("sources.main.result.ordered_on.maxLength: 'maxLength'"
                        + " is not a key a result: declaration reads"));
    }

    /** Decision 23: a domain's locale is legal on an input (not applied); written there it is not. */
    @Test
    void aLocaleOnAnInputIsAnErrorUnlessItComesFromTheDomain(@TempDir Path dir)
            throws Exception {
        List<LintFinding> clean = new AppLinter().lint(app(dir.resolve("clean"),
                "input:\n  amount: { domain: eur_amount }",
                "    result:\n      amount: { domain: eur_amount }"));
        assertThat(clean).noneMatch(f -> f.code().equals(KIND));

        List<LintFinding> findings = new AppLinter().lint(app(dir.resolve("written"),
                "input:\n  amount: { type: number, format: \"#,##0.00\", locale: de-DE }",
                "    result: {}"));
        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("input.amount.locale: locale: is not applied on an"
                        + " input"));
    }

    @Test
    void aLocaleTheJdkCannotFormatOnAResultEntryIsAnError(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "",
                "    result:\n      amount: { type: number, locale: ja_JP }"));

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.message().contains("sources.main.result.amount.locale: 'ja_JP' is not a"
                        + " language tag the JDK can format"));
    }

    /**
     * An input's {@code default:} is judged by the input's own rules (docs/audit-low-leads.md
     * XD-07b, {@code TQL-YAML-1072}): {@code type: number, default: abc} linted clean, booted,
     * and answered 200 to every request that omitted the input — 500 once bound into a numeric
     * compare — while a caller sending the same text was refused.
     */
    @Test
    void aDefaultTheInputRefusesIsAnErrorAtItsLine(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, """
                input:
                  n:
                    type: number
                    default: abc
                  dir:
                    type: string
                    enum: [asc, desc]
                    default: up
                  ok:
                    type: integer
                    default: "5"
                """, ""));

        assertThat(findings).filteredOn(f -> f.code().equals("TQL-YAML-1072")).hasSize(2)
                .allSatisfy(f -> {
                    assertThat(f.isError()).isTrue();
                    assertThat(f.source()).isEqualTo("web/orders/get.yml");
                    assertThat(f.line()).isNotNull();
                })
                .anySatisfy(f -> assertThat(f.message()).contains("route 'orders.list'",
                        "input.n.default", "'abc'", "not a number"))
                .anySatisfy(f -> assertThat(f.message()).contains("input.dir.default", "'up'",
                        "not one of [asc, desc]"));
    }

    @Test
    void aJobsInputDefaultIsJudgedLikeARoutes(@TempDir Path dir) throws Exception {
        Path app = app(dir, "", "");
        Files.createDirectories(app.resolve("batch/load"));
        Files.writeString(app.resolve("batch/load/job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                input:
                  since:
                    type: date
                    default: yesterday
                pipeline:
                  - id: load
                    sql:
                      file: load.sql
                      mode: update
                """);
        Files.writeString(app.resolve("batch/load/load.sql"),
                "delete from src where created < /* params.since */'2026-01-01'\n");

        assertThat(new AppLinter().lint(app)).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("TQL-YAML-1072");
            assertThat(f.source()).isEqualTo("batch/load/job.yml");
            assertThat(f.message()).contains("job 'nightly'", "input.since.default",
                    "'yesterday'", "not a valid date");
        });
    }

    /**
     * The export recipes apply no declaration (docs/audit-low-leads.md, the {@code result:}
     * sweep): a {@code result:} on a query-export's source linted clean and booted, and the
     * csv carried the raw text.
     */
    @Test
    void aDeclarationOnAnExportRecipeIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, "", "");
        Files.createDirectories(app.resolve("web/dump"));
        Files.writeString(app.resolve("web/dump/get.yml"), """
                version: tesseraql/v1
                id: orders.dump
                kind: route
                recipe: query-export
                security:
                  auth: public
                export:
                  format: csv
                sources:
                  main:
                    sql:
                      file: dump.sql
                    result:
                      note: { type: json }
                """);
        Files.writeString(app.resolve("web/dump/dump.sql"), "select id, note from orders\n");

        assertThat(new AppLinter().lint(app)).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo(KIND);
            assertThat(f.isError()).isTrue();
            assertThat(f.source()).isEqualTo("web/dump/get.yml");
            assertThat(f.message()).contains("route 'orders.dump' sources.main.result:",
                    "query-export hands every source to the export writer");
        });
    }

    @Test
    void aDeclarationOnAChunkReaderIsAnError(@TempDir Path dir) throws Exception {
        Path app = app(dir, "", "    result: {}");
        Files.createDirectories(app.resolve("batch/load"));
        Files.writeString(app.resolve("batch/load/job.yml"), """
                version: tesseraql/v1
                id: nightly
                kind: job
                recipe: batch-pipeline
                pipeline:
                  - id: load
                    chunk:
                      reader:
                        sql:
                          file: read.sql
                        result:
                          payload: { type: json }
                      writer:
                        sql:
                          file: write.sql
                """);
        Files.writeString(app.resolve("batch/load/read.sql"),
                "select id, payload from src order by id\n");
        Files.writeString(app.resolve("batch/load/write.sql"),
                "insert into dst (id) values (/* row.id */1)\n");

        List<LintFinding> findings = new AppLinter().lint(app);

        assertThat(findings).anyMatch(f -> f.code().equals(KIND) && f.isError()
                && f.source().equals("batch/load/job.yml")
                && f.message().contains("chunk.reader.result: is not applied"));
    }
}
