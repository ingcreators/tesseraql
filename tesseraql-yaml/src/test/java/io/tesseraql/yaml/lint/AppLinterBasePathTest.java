package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The root-absolute link warning (docs/base-path.md decision 3) — TQL-TPL-2004.
 *
 * <p>An application served under a prefix that writes {@code href="/orders"} emits an address its
 * own runtime does not answer at. The framework cannot tell a mistake from a deliberate link
 * outside the mount point, so it warns; and it says nothing at all to an application served at
 * the root of its origin, which is every application that has not asked for a prefix.
 */
class AppLinterBasePathTest {

    private static void writeApp(Path dir, String basePath, String page) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                %s""".formatted(basePath == null ? "" : """
                  http:
                    basePath: %s
                """.formatted(basePath)));
        Files.createDirectories(dir.resolve("web/orders"));
        Files.writeString(dir.resolve("web/orders/orders.sql"), "select 1 as id\n");
        Files.writeString(dir.resolve("web/orders/page.html"), page);
        Files.writeString(dir.resolve("web/orders/get.yml"), """
                version: tesseraql/v1
                id: orders.list
                kind: route
                recipe: query-html
                security:
                  auth: public
                sql:
                  file: orders.sql
                response:
                  html:
                    template: page.html
                """);
    }

    @Test
    void aRootAbsoluteLinkUnderAPrefixWarns(@TempDir Path dir) throws Exception {
        writeApp(dir, "/apps/shop-a", "<a href=\"/orders/42\">order</a>");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(finding -> "TQL-TPL-2004".equals(finding.code())
                        && !finding.isError()
                        && finding.message().contains("th:href=\"@{/orders/42}\""))
                .noneMatch(LintFinding::isError);
    }

    /** The same URL written as a literal substitution, which reads as though it were dynamic. */
    @Test
    void aLiteralSubstitutionWarnsToo(@TempDir Path dir) throws Exception {
        writeApp(dir, "/apps/shop-a", "<a th:href=\"|/orders/${id}|\">order</a>");

        assertThat(new AppLinter().lint(dir))
                .anyMatch(finding -> "TQL-TPL-2004".equals(finding.code()));
    }

    @Test
    void aLinkExpressionIsWhatTheWarningAsksFor(@TempDir Path dir) throws Exception {
        writeApp(dir, "/apps/shop-a", "<a th:href=\"@{/orders/42}\">order</a>"
                + "<a href=\"https://example.test\">off-site</a>"
                + "<a href=\"//cdn.example.test/x\">protocol-relative</a>"
                + "<a href=\"orders/42\">relative</a>");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(finding -> "TQL-TPL-2004".equals(finding.code()));
    }

    @Test
    void anApplicationWithNoPrefixIsNeverWarned(@TempDir Path dir) throws Exception {
        writeApp(dir, null, "<a href=\"/orders/42\">order</a>");

        assertThat(new AppLinter().lint(dir))
                .noneMatch(finding -> "TQL-TPL-2004".equals(finding.code()));
    }
}
