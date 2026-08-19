package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The authorization server is the stack's (docs/token-issuance.md decision 8): an application
 * declaring {@code tesseraql.security.oauth.enabled} in its own tree is told so here, before
 * the same declaration meets the boot refusal as a stack member.
 */
class AppLinterOAuthScopeTest {

    private Path app(Path dir, String security) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"),
                "tesseraql:\n  app:\n    name: t\n" + security);
        return dir;
    }

    @Test
    void anApplicationEnablingTheAuthorizationServerIsReported(@TempDir Path dir)
            throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir,
                "  security:\n    oauth:\n      enabled: true\n"));

        assertThat(findings)
                .filteredOn(finding -> "TQL-OAUTH-3004".equals(finding.code()))
                .extracting(LintFinding::severity)
                .containsExactly("error");
    }

    @Test
    void anApplicationWithoutTheKeyIsQuiet(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, ""));

        assertThat(findings).extracting(LintFinding::code).doesNotContain("TQL-OAUTH-3004");
    }
}
