package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Lints around application MCP prompts (docs/prompt-as-recipe.md slice 3).
 *
 * <p>A prompt argument is a full {@code InputField}, and most of it is live on a prompt — the
 * binder coerces and validates the argument by it. The three keys that are not are refused here
 * rather than silently accepted, and the read-only refusal the compiler makes at startup is
 * said at build time, where the author is still reading the document.
 */
class AppLinterPromptTest {

    /** An app whose one prompt carries {@code body} — the document below {@code description:}. */
    private Path app(@TempDir Path dir, String body) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("mcp"));
        Files.writeString(dir.resolve("mcp/welcome.txt.tpl"), "Hello [(${name})]\n");
        Files.writeString(dir.resolve("mcp/customers.sql"), "select 1\n");
        Files.writeString(dir.resolve("mcp/welcome.yml"), """
                version: tesseraql/v1
                id: draft.welcome
                kind: prompt
                recipe: prompt-text
                description: Draft a welcome message.
                %s
                response:
                  text:
                    template: welcome.txt.tpl
                """.formatted(body));
        return dir;
    }

    private List<LintFinding> promptFindings(Path app, String code) {
        return new AppLinter().lint(app).stream().filter(f -> f.code().equals(code)).toList();
    }

    @Test
    void refusesAnArgumentPolicy(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                input:
                  name:
                    type: string
                    policy: customers.read
                """);

        assertThat(promptFindings(app, "TQL-MCP-1015")).singleElement().satisfies(finding -> {
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("draft.welcome").contains("'name'")
                    .contains("policy:").contains("security.policy:");
        });
    }

    @Test
    void refusesANonWritableArgument(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                input:
                  name:
                    type: string
                    writable: false
                """);

        assertThat(promptFindings(app, "TQL-MCP-1015")).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("writable:")
                        .contains("no other source"));
    }

    @Test
    void refusesAnArgumentWidget(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                input:
                  name:
                    type: string
                    widget: code
                """);

        assertThat(promptFindings(app, "TQL-MCP-1015")).singleElement().satisfies(
                finding -> assertThat(finding.message()).contains("widget:").contains("form"));
    }

    /**
     * Everything else {@code InputField} carries is wired on a prompt: the binder coerces and
     * validates by it, {@code description:} is advertised in {@code prompts/list}, and
     * {@code classification:}/{@code mask:} keep the argument out of the route audit trail.
     */
    @Test
    void acceptsTheKeysAPromptActsOn(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                input:
                  name:
                    type: string
                    required: true
                    minLength: 2
                    maxLength: 40
                    pattern: '[A-Za-z ]+'
                    description: The new customer's name.
                  email:
                    type: string
                    format: email
                    classification: pii
                    mask: email
                  tone:
                    type: string
                    default: warm
                    enum: [warm, formal]
                  limit:
                    type: integer
                    min: 1
                    max: 100
                    requiredWhen: params.tone == 'formal'
                """);

        assertThat(promptFindings(app, "TQL-MCP-1015")).isEmpty();
    }

    /**
     * A key a shared domain supplies was not written here. Refusing it would make a domain
     * unusable from a prompt over a key that is merely inert there — a tax on reuse, not a
     * defect caught.
     */
    @Test
    void acceptsADomainSuppliedWidget(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                input:
                  sku:
                    domain: sku
                """);
        Files.createDirectories(app.resolve("domains"));
        Files.writeString(app.resolve("domains/catalog.yml"), """
                version: tesseraql/v1
                domains:
                  sku:
                    type: string
                    maxLength: 40
                    widget: code
                """);

        assertThat(promptFindings(app, "TQL-MCP-1015")).isEmpty();
    }

    @Test
    void refusesCommandSteps(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                steps:
                  - id: log
                    sql:
                      file: customers.sql
                """);

        assertThat(promptFindings(app, "TQL-MCP-1016")).singleElement().satisfies(finding -> {
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("steps:").contains("read");
        });
    }

    @Test
    void refusesAWritingSource(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                sources:
                  main:
                    sql:
                      file: customers.sql
                      mode: update
                """);

        assertThat(promptFindings(app, "TQL-MCP-1016")).singleElement()
                .satisfies(finding -> assertThat(finding.message()).contains("'main'")
                        .contains("update mode"));
    }

    /** Reading data is the point of the recipe, so a prompt that reads lints clean. */
    @Test
    void acceptsAPromptThatReads(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                security:
                  auth: bearer
                input:
                  customerId:
                    type: integer
                    required: true
                sources:
                  main:
                    sql:
                      file: customers.sql
                      params:
                        customerId: params.customerId
                """);

        assertThat(new AppLinter().lint(app))
                .noneMatch(finding -> finding.code().startsWith("TQL-MCP-"));
    }
    /**
     * A prompt reads data now, so it answers for that data the way every other mcp kind does.
     * These checks existed on tools and resources all along; a prompt had nothing to check
     * until this campaign gave it {@code sources:}.
     */
    @Test
    void aMissingSourceFileIsABuildError(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                sources:
                  customer:
                    sql:
                      file: no-such-file.sql
                """);

        assertThat(promptFindings(app, "TQL-SQL-2103")).singleElement().satisfies(finding -> {
            assertThat(finding.isError()).isTrue();
            assertThat(finding.message()).contains("customer").contains("no-such-file.sql");
        });
    }

    @Test
    void anUndefinedPolicyIsAWarningLikeEverywhereElse(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                security:
                  policy: nobody.declared.this
                sources:
                  customer:
                    sql:
                      file: customers.sql
                """);

        assertThat(promptFindings(app, "TQL-SEC-4030")).singleElement().satisfies(
                finding -> assertThat(finding.message()).contains("nobody.declared.this"));
    }

    @Test
    void aPromptThatReadsAnExistingFileIsClean(@TempDir Path dir) throws Exception {
        Path app = app(dir, """
                sources:
                  customer:
                    sql:
                      file: customers.sql
                """);

        assertThat(promptFindings(app, "TQL-SQL-2103")).isEmpty();
        assertThat(promptFindings(app, "TQL-SEC-4030")).isEmpty();
    }

}
