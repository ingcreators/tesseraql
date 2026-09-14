package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppLinterI18nTest {

    @Test
    void noMessagesDirectoryMeansNoI18nFindings(@TempDir Path dir) throws Exception {
        baseApp(dir);
        assertThat(new AppLinter().lint(dir))
                .noneMatch(f -> f.code().startsWith("TQL-YAML-10") && f.code().endsWith("7")
                        || f.code().equals("TQL-YAML-1008") || f.code().equals("TQL-YAML-1103")
                        || f.code().equals("TQL-FIELD-2005"));
    }

    @Test
    void malformedCatalogFilenameIsAnError(@TempDir Path dir) throws Exception {
        baseApp(dir);
        Files.createDirectories(dir.resolve("messages"));
        Files.writeString(dir.resolve("messages/not_a_tag.yml"), "k: v\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1007") && f.isError());
    }

    @Test
    void translationGapsAndUndeclaredLocalesWarn(@TempDir Path dir) throws Exception {
        baseApp(dir);
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  i18n:
                    defaultLocale: en
                    locales: [en, ja, de]
                """);
        Files.createDirectories(dir.resolve("messages"));
        Files.writeString(dir.resolve("messages/en.yml"), "a: A\nb: B\n");
        Files.writeString(dir.resolve("messages/ja.yml"), "a: あ\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1008") && !f.isError()
                && f.message().contains("'ja'") && f.message().contains("b"));
        assertThat(findings).anyMatch(f -> f.code().equals("TQL-YAML-1103") && !f.isError()
                && f.message().contains("'de'"));
    }

    /**
     * A declared locale the runtime cannot serve is one finding per entry, at its key — and
     * not a crash: with {@code ja_JP} folded to {@code und} the catalog walk below used to throw
     * out of the linter, and the catalog-language rule would build the settings the boot
     * refuses. Both rules see the same predicate.
     */
    @Test
    void aDeclaredLocaleTheJdkCannotFormatIsAnErrorAtItsKey(@TempDir Path dir)
            throws Exception {
        baseApp(dir);
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  i18n:
                    defaultLocale: ja_JP
                    locales: [en, ja_JP]
                """);
        Files.createDirectories(dir.resolve("messages"));
        Files.writeString(dir.resolve("messages/en.yml"), "a: A\n");
        Files.createDirectories(dir.resolve("catalogs"));
        Files.writeString(dir.resolve("catalogs/codes.yml"), """
                version: tesseraql/v1
                catalogs:
                  kinds:
                    table: kind_master
                    key: code
                    label: name
                    language: lang
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-YAML-1065"))
                .allMatch(LintFinding::isError)
                .extracting(LintFinding::message)
                .containsExactly(
                        "tesseraql.i18n.defaultLocale: 'ja_JP' is not a language tag the JDK"
                                + " can format (expected e.g. en, ja-JP)",
                        "tesseraql.i18n.locales[1]: 'ja_JP' is not a language tag the JDK"
                                + " can format (expected e.g. en, ja-JP)");
        assertThat(findings).extracting(LintFinding::code)
                .doesNotContain("TQL-YAML-1103", "TQL-YAML-1008", "TQL-FIELD-4619");
    }

    @Test
    void unresolvableValidationMessageKeysWarn(@TempDir Path dir) throws Exception {
        baseApp(dir);
        Files.createDirectories(dir.resolve("messages"));
        Files.writeString(dir.resolve("messages/en.yml"), "members.known: Known.\n");
        Files.createDirectories(dir.resolve("web/api/members"));
        Files.writeString(dir.resolve("web/api/members/post.yml"), """
                version: tesseraql/v1
                id: members.register
                kind: route
                recipe: command-json
                security:
                  auth: bearer
                validate:
                  known:
                    rule: body.a != null
                    field: a
                    message: members.known
                  unknown:
                    rule: body.b != null
                    field: b
                    message: members.unknown
                  builtin:
                    rule: body.c != null
                    field: c
                    message: tql.input.required
                steps:
                  - id: main
                    sql:
                      file: insert.sql
                      mode: update
                response:
                  json:
                    body:
                      ok: params.a
                """);
        Files.writeString(dir.resolve("web/api/members/insert.sql"),
                "insert into members (a) values (/* a */'x')\n");

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings).anyMatch(f -> f.code().equals("TQL-FIELD-2005")
                && f.message().contains("members.unknown"));
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-2005")
                && (f.message().contains("members.known")
                        || f.message().contains("tql.input.required")));
    }

    private static void baseApp(Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n");
    }
}
