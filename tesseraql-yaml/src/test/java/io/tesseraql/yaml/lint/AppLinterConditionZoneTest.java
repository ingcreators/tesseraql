package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The app-wide {@code tesseraql.security.conditions.zone} literal, judged at lint by the zone
 * predicate every declared zone shares (docs/audit-low-leads.md slice 8, XD-07f). No lint
 * read the key before: a misspelt region took the boot down with the JDK's own sentence,
 * naming neither the key nor the application.
 */
class AppLinterConditionZoneTest {

    private static Path app(Path dir, String zoneLine) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                  security:
                    conditions:
                %s
                """.formatted(zoneLine));
        return dir;
    }

    private static List<LintFinding> of(List<LintFinding> findings, String code) {
        return findings.stream().filter(finding -> finding.code().equals(code)).toList();
    }

    @Test
    void aMisspeltZoneIsRefusedNamingTheKey(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "      zone: Asia/Tokio"));
        assertThat(of(findings, "TQL-SEC-4147")).singleElement().satisfies(finding -> {
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("config");
            assertThat(finding.message())
                    .startsWith("tesseraql.security.conditions.zone: 'Asia/Tokio' is not a"
                            + " time-zone id the JDK knows");
        });
    }

    @Test
    void aZoneReadAsWrittenAndAnAbsentOneLintClean(@TempDir Path dir) throws Exception {
        assertThat(of(new AppLinter().lint(app(dir, "      zone: Asia/Tokyo")), "TQL-SEC-4147"))
                .isEmpty();
        assertThat(of(new AppLinter().lint(app(dir.resolve("none"), "      hours: {}")),
                "TQL-SEC-4147")).isEmpty();
        // Read as declared, the way the files zone is (docs/export-declarations.md decision
        // 14): padding is part of the value, and the finding shows it.
        assertThat(of(new AppLinter().lint(app(dir.resolve("padded"), "      zone: ' Asia/Tokyo'")),
                "TQL-SEC-4147")).singleElement().satisfies(
                        finding -> assertThat(
                                finding.message()).contains("' Asia/Tokyo'"));
    }
}
