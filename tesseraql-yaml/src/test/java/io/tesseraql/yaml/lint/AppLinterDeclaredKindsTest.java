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
