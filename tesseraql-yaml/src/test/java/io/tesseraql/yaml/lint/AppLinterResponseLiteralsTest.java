package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The response literals the edge writes as given, as findings (docs/audit-low-leads.md EH-06):
 * a file response's {@code charset=} the body is not written in ({@code TQL-YAML-1073}) and a
 * redirect location with whitespace at either end ({@code TQL-YAML-1074}). No lint read either
 * key; the charset was a 200 whose header contradicted its bytes, the leading space a redirect
 * that left the application.
 */
class AppLinterResponseLiteralsTest {

    /** A page serving a file response, or a command answering a redirect — the two literals' homes. */
    private static Path app(Path dir, String recipe, String response) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), "tesseraql:\n  app:\n    name: t\n");
        Files.createDirectories(dir.resolve("web/receipt"));
        Files.writeString(dir.resolve("web/receipt/receipt.txt"), "Total: [(${total})]\n");
        String work = "page".equals(recipe)
                ? "sources:\n  main:\n    sql:\n      file: total.sql\n"
                : "steps:\n  - id: mark\n    sql:\n      file: mark.sql\n      mode: update\n";
        Files.writeString(dir.resolve("web/receipt/post.yml"), """
                version: tesseraql/v1
                id: receipt.print
                kind: route
                recipe: %s
                security:
                  auth: public
                %s
                response:
                %s
                """.formatted(recipe, work, response));
        Files.writeString(dir.resolve("web/receipt/mark.sql"),
                "update receipts set printed = true where id = 1\n");
        Files.writeString(dir.resolve("web/receipt/total.sql"), "select 1 as total\n");
        return dir;
    }

    @Test
    void aCharsetTheBodyIsNotWrittenInIsAnErrorAtItsLine(@TempDir Path dir) throws Exception {
        List<LintFinding> findings = new AppLinter().lint(app(dir, "page", """
                  file:
                    template: receipt.txt
                    contentType: "text/plain; charset=Shift_JIS"
                """));

        assertThat(findings).filteredOn(f -> "TQL-YAML-1073".equals(f.code())).singleElement()
                .satisfies(f -> {
                    assertThat(f.isError()).isTrue();
                    assertThat(f.source()).isEqualTo("web/receipt/post.yml");
                    assertThat(f.line()).isNotNull();
                    assertThat(f.message()).contains("route 'receipt.print'",
                            "response.file.contentType", "charset=Shift_JIS",
                            "the body is written as UTF-8");
                });
        assertThat(new AppLinter().lint(app(dir.resolve("utf8"), "page", """
                  file:
                    template: receipt.txt
                    contentType: "text/plain; charset=utf-8"
                """))).noneMatch(f -> "TQL-YAML-1073".equals(f.code()));
    }

    @Test
    void whitespaceAtEitherEndOfARedirectLocationIsAnError(@TempDir Path dir) throws Exception {
        for (String location : List.of("\"/receipts \"", "\" /receipts\"")) {
            assertThat(new AppLinter().lint(app(dir.resolve(String.valueOf(location.hashCode())),
                    "command-json", "  redirect:\n    location: " + location + "\n")))
                    .as(location).filteredOn(f -> "TQL-YAML-1074".equals(f.code()))
                    .singleElement().satisfies(f -> {
                        assertThat(f.isError()).isTrue();
                        assertThat(f.message()).contains("route 'receipt.print'",
                                "response.redirect.location", "whitespace at its");
                    });
        }
        assertThat(new AppLinter().lint(app(dir.resolve("clean"), "command-json",
                "  redirect:\n    location: /receipts/{path.id}\n")))
                .noneMatch(f -> "TQL-YAML-1074".equals(f.code()));
    }
}
