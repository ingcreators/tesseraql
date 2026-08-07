package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mail wiring lints (docs/pages-and-mail-lints.md D2): template existence at build
 * time ({@code TQL-BATCH-5304}), unknown {@code tql/email} fragments
 * ({@code TQL-TPL-2002}, shadow-aware), and unresolvable model roots
 * ({@code TQL-TPL-2003} — the helpdesk {@code ${ticket}} bug class).
 */
class AppLinterMailTest {

    private static void writeApp(Path dir, String template) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  notifications:
                    channels:
                      agent-mail:
                        type: mail
                        host: localhost
                        from: app@example.com
                        to: ops@example.com
                        subject: "Ticket [(${payload.ticket})]"
                        template: %s
                """.formatted(template));
    }

    private static List<LintFinding> mailFindings(List<LintFinding> findings) {
        return findings.stream()
                .filter(f -> f.code().startsWith("TQL-TPL")
                        || "TQL-BATCH-5304".equals(f.code()))
                .toList();
    }

    @Test
    void aWellWiredHtmlMailProducesNoFindings(@TempDir Path dir) throws Exception {
        writeApp(dir, "templates/mail/notice.html");
        Files.createDirectories(dir.resolve("templates/mail"));
        Files.writeString(dir.resolve("templates/mail/notice.html"),
                """
                        <div th:replace="~{tql/email/hc-email-layout :: hcLayout('T', 'P', ~{:: content})}">
                          <div th:fragment="content">
                            <div th:replace="~{tql/email/hc-email :: hcText(|Hello ${payload.name}|)}"></div>
                            <table><tr th:each="row : ${payload.rows}"><td th:text="${row.value}"></td></tr></table>
                            <p th:with="who=${payload.name}" th:text="${who}"></p>
                            <div th:replace="~{tql/email/hc-email :: hcFooter(|Sent by ${event.app}|)}"></div>
                          </div>
                        </div>
                        """);

        assertThat(mailFindings(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aMissingTemplateFileFailsTheBuild(@TempDir Path dir) throws Exception {
        writeApp(dir, "templates/mail/nope.html");

        List<LintFinding> findings = mailFindings(new AppLinter().lint(dir));

        assertThat(findings).singleElement().satisfies(f -> {
            assertThat(f.code()).isEqualTo("TQL-BATCH-5304");
            assertThat(f.severity()).isEqualTo("error");
            assertThat(f.message()).contains("agent-mail").contains("nope.html");
        });
    }

    @Test
    void anUnknownEmailFragmentFailsTheBuild(@TempDir Path dir) throws Exception {
        writeApp(dir, "templates/mail/notice.html");
        Files.createDirectories(dir.resolve("templates/mail"));
        Files.writeString(dir.resolve("templates/mail/notice.html"),
                """
                        <div th:replace="~{tql/email/hc-email :: hcShinyButton(${payload.url}, 'Go')}"></div>
                        """);

        List<LintFinding> findings = mailFindings(new AppLinter().lint(dir));

        assertThat(findings).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("TQL-TPL-2002");
            assertThat(f.severity()).isEqualTo("error");
            assertThat(f.message()).contains("hcShinyButton").contains("hcButton");
        });
    }

    @Test
    void aShadowedLibraryDefinesTheContract(@TempDir Path dir) throws Exception {
        writeApp(dir, "templates/mail/notice.html");
        Files.createDirectories(dir.resolve("templates/mail"));
        Files.createDirectories(dir.resolve("templates/tql/email"));
        // The app's re-themed eject declares a fragment the bundled library lacks.
        Files.writeString(dir.resolve("templates/tql/email/hc-email.html"), """
                <p th:fragment="hcShinyButton(href, label)" th:text="${label}"></p>
                """);
        Files.writeString(dir.resolve("templates/mail/notice.html"),
                """
                        <div th:replace="~{tql/email/hc-email :: hcShinyButton(${payload.url}, 'Go')}"></div>
                        """);

        assertThat(mailFindings(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void unresolvableRootsWarnInBodyAndSubject(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  notifications:
                    channels:
                      agent-mail:
                        type: mail
                        host: localhost
                        from: app@example.com
                        subject: "Ticket [(${ticket})] needs you"
                        template: templates/mail/assigned.txt
                """);
        Files.createDirectories(dir.resolve("templates/mail"));
        // The shipped-for-months helpdesk bug: bare roots instead of payload.*.
        Files.writeString(dir.resolve("templates/mail/assigned.txt"),
                "Ticket [(${ticket})] was assigned. Priority: [(${priority})].\n");

        List<LintFinding> findings = mailFindings(new AppLinter().lint(dir));

        assertThat(findings).allSatisfy(f -> {
            assertThat(f.code()).isEqualTo("TQL-TPL-2003");
            assertThat(f.severity()).isEqualTo("warning");
        });
        assertThat(findings).extracting(LintFinding::message)
                .anySatisfy(m -> assertThat(m).contains("subject").contains("${ticket"))
                .anySatisfy(m -> assertThat(m).contains("template").contains("${ticket"))
                .anySatisfy(m -> assertThat(m).contains("template").contains("${priority"));
    }

    @Test
    void anEnvDependentTemplateValueIsSkipped(@TempDir Path dir) throws Exception {
        writeApp(dir, "${MAIL_TEMPLATE:templates/mail/nope.html}");

        assertThat(mailFindings(new AppLinter().lint(dir))).isEmpty();
    }
}
