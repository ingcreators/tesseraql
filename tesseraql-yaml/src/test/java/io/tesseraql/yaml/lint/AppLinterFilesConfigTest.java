package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code tesseraql.files.locale} / {@code tesseraql.files.timezone} are literals every export
 * of the app falls back to, so a typo there is an app-wide outage discovered at the first
 * export (docs/export-declarations.md). Judged once, at lint and at compile; an unresolved
 * placeholder is skipped at lint (the environment is the runtime's), never refused.
 */
class AppLinterFilesConfigTest {

    private static Path app(Path dir, String filesBlock) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n  files:\n" + filesBlock);
        return dir;
    }

    @Test
    void aMistypedConfigZoneIsRefusedNamingTheKey(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "    timezone: Asia/Tokio\n"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("config");
            assertThat(finding.message()).contains("app 't'", "tesseraql.files.timezone",
                    "'Asia/Tokio'");
        });
    }

    @Test
    void aMistypedConfigLocaleIsRefusedNamingTheKey(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, "    locale: ja_JP\n"))).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
            assertThat(finding.message()).contains("tesseraql.files.locale", "'ja_JP'");
        });
    }

    @Test
    void aConfigKeyThatIsASourceExpressionIsRefused(@TempDir Path dir) throws Exception {
        // Decision 5: a config key has no input: to bind against.
        assertThat(new AppLinter().lint(app(dir, "    timezone: query.tz\n")))
                .anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
                    assertThat(finding.message()).contains("tesseraql.files.timezone", "'query.tz'",
                            "no request");
                });
    }

    @Test
    void aValidConfigKeyIsClean(@TempDir Path dir) throws Exception {
        assertThat(new AppLinter().lint(app(dir, "    timezone: Asia/Tokyo\n    locale: ja-JP\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void anUnresolvedPlaceholderIsSkippedNotRefused(@TempDir Path dir) throws Exception {
        // The variable is the deployment's; the linter neither knows it nor refuses it.
        assertThat(new AppLinter().lint(app(dir, "    timezone: ${TQL_TEST_UNSET_ZONE}\n")))
                .noneMatch(finding -> "TQL-YAML-1063".equals(finding.code()));
    }

    @Test
    void aPlaceholderWithADefaultIsJudgedOnItsResolvedValue(@TempDir Path dir) throws Exception {
        // AppConfig resolves ${key:default}; a rule reading the raw node would skip it while
        // the boot refuses it — the lint/boot drift the one predicate exists to preclude.
        assertThat(new AppLinter().lint(app(dir,
                "    timezone: ${TQL_TEST_TZ_THAT_IS_NOT_SET:Asia/Tokio}\n")))
                .anySatisfy(finding -> {
                    assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
                    assertThat(finding.message()).contains("tesseraql.files.timezone",
                            "'Asia/Tokio'");
                });
    }

    @Test
    void aMistypedConfigLocaleIsRefusedBesideTheZone(@TempDir Path dir) throws Exception {
        // Both keys, one loop: a loop that lists the zone only leaves the locale unjudged.
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "    timezone: Asia/Tokyo\n    locale: japanese\n"));

        assertThat(findings).anySatisfy(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-YAML-1063");
            assertThat(finding.message()).contains("tesseraql.files.locale", "'japanese'");
        });
    }
}
