package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lint rules for the declared-input vocabulary (roadmap Phase 40, {@code TQL-YAML-1011..1014}). */
class AppLinterInputTest {

    private static void writeRoute(Path dir, String method, String inputBlock) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/items/" + method + ".yml"), """
                version: tesseraql/v1
                id: items.probe
                kind: route
                recipe: query-json
                %s
                sql:
                  file: list.sql
                response:
                  json:
                    body:
                      data: sql.rows
                """.formatted(inputBlock));
    }

    private static List<String> codes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code)
                .filter(c -> c.startsWith("TQL-YAML-101")).toList();
    }

    @Test
    void aHeadRouteFileIsRejectedWithAClearCode(@TempDir Path dir) throws Exception {
        writeRoute(dir, "head", "");
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1011");
    }

    @Test
    void aBrokenPatternIsAnError(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", """
                input:
                  code:
                    type: string
                    pattern: "[unclosed"
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1012");
    }

    @Test
    void anUnknownStringFormatIsAnError(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", """
                input:
                  mail:
                    type: string
                    format: emial
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1013");
    }

    @Test
    void aDateParsePatternIsNotAStringFormat(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", """
                input:
                  orderDate:
                    type: date
                    format: yyyy/MM/dd
                """);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }

    @Test
    void aBrokenRequiredWhenIsAnError(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", """
                input:
                  note:
                    type: string
                    requiredWhen: "params.kind =="
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1014");
    }

    @Test
    void wellFormedConstraintsAreClean(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", """
                input:
                  mail:
                    type: string
                    format: email
                    pattern: ".+@example[.]com"
                    minLength: 5
                  price:
                    type: number
                    min: 0.5
                    max: 99.5
                  note:
                    type: string
                    requiredWhen: params.mail != null
                """);
        assertThat(codes(new AppLinter().lint(dir))).isEmpty();
    }
}
