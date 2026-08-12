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
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      data: main.rows
                """.formatted(inputBlock));
    }

    private static List<String> codes(List<LintFinding> findings) {
        return findings.stream().map(LintFinding::code)
                .filter(c -> c.startsWith("TQL-YAML-101") || c.startsWith("TQL-YAML-102")
                        || c.startsWith("TQL-YAML-104"))
                .toList();
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
    void aDanglingNestIsAnError(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.probe
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      orders: main.rows
                    nest:
                      - into: orders
                        children: ghost
                        as: lines
                        on: { id: order_id }
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1019");
    }

    @Test
    void anAttachingNestIsAccepted(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(nestRoute(dir, """
                on: { id: order_id }
                        as: lines""")))).isEmpty();
    }

    @Test
    void aMergingNestOverACompositeKeyIsAccepted(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(nestRoute(dir, """
                on: { buyer_code: buyer, supplier_code: supplier }
                        merge: [partner_name]""")))).isEmpty();
    }

    @Test
    void aNestDeclaringBothAsAndMergeIsAnError(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(nestRoute(dir, """
                on: { id: order_id }
                        as: lines
                        merge: [partner_name]""")))).contains("TQL-YAML-1019");
    }

    @Test
    void aNestComposingNothingIsAnError(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(nestRoute(dir, "on: { id: order_id }"))))
                .contains("TQL-YAML-1019");
    }

    /** A route whose one nest entry joins and composes as {@code composition} declares. */
    private static Path nestRoute(Path dir, String composition) throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/items/lines.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.probe
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: list.sql
                  lines:
                    sql:
                      file: lines.sql
                response:
                  json:
                    body:
                      orders: main.rows
                    nest:
                      - into: orders
                        children: lines
                        %s
                """.formatted(composition));
        return dir;
    }

    @Test
    void anEnrichmentOverADeclaredResultIsAccepted(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(enrichRoute(dir, """
                on: { partner_code: code }
                        sql:
                          file: partners.sql
                        merge: [partner_name]""", KEYED_REFERENCE)))).isEmpty();
    }

    @Test
    void anEnrichmentComposingNothingIsAnError(@TempDir Path dir) throws Exception {
        assertThat(codes(new AppLinter().lint(enrichRoute(dir, """
                on: { partner_code: code }
                        sql:\n                          file: partners.sql""", KEYED_REFERENCE))))
                .contains("TQL-YAML-1047");
    }

    @Test
    void aReferenceThatNeverBindsTheKeysIsAnError(@TempDir Path dir) throws Exception {
        // It returns the right answer and reads the whole table once per batch, so only the
        // build can see the mistake.
        assertThat(codes(new AppLinter().lint(enrichRoute(dir, """
                on: { partner_code: code }
                        sql:
                          file: partners.sql
                        merge: [partner_name]""",
                "select code, name as partner_name from partners\n"))))
                .contains("TQL-YAML-1048");
    }

    private static final String KEYED_REFERENCE = "select code, name as partner_name from partners where code in /* keys */('P1')\n";

    /** A route whose one enrichment is declared as {@code enrichment} over {@code reference}. */
    private static Path enrichRoute(Path dir, String enrichment, String reference)
            throws Exception {
        Files.createDirectories(dir.resolve("web/items"));
        Files.writeString(dir.resolve("web/items/list.sql"), "select 1 as one\n");
        Files.writeString(dir.resolve("web/items/partners.sql"), reference);
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.probe
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: list.sql
                    enrich:
                      partner:
                        %s
                response:
                  json:
                    body:
                      rows: main.rows
                """.formatted(enrichment));
        return dir;
    }

    @Test
    void aBrokenStatusWhenIsAnError(@TempDir Path dir) throws Exception {
        writeRoute(dir, "get", "");
        Files.writeString(dir.resolve("web/items/get.yml"), """
                version: tesseraql/v1
                id: items.probe
                kind: route
                recipe: query-json
                sources:
                  main:
                    sql:
                      file: list.sql
                response:
                  json:
                    body:
                      data: main.rows
                    statusWhen:
                      - when: "main.rowCount =="
                        status: 404
                """);
        assertThat(codes(new AppLinter().lint(dir))).contains("TQL-YAML-1020");
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
