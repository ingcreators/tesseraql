package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A command step writes: it publishes affectedRows and keys, never rows, so an
 * {@code enrich:} on it has nothing to fold into and the compiler drops it
 * (docs/lookups.md). The rule shared {@code TQL-FIELD-2004} with the batch step-shape
 * checks until docs/lint-restructure.md decision 5 gave it {@code TQL-FIELD-2009} — one
 * code, one question.
 */
class AppLinterCommandEnrichTest {

    @Test
    void aCommandStepsEnrichHasNoRowsToFoldInto(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/tesseraql.yml"), """
                tesseraql:
                  app:
                    name: t
                """);
        Files.createDirectories(dir.resolve("web/api/orders"));
        Files.writeString(dir.resolve("web/api/orders/insert.sql"),
                "insert into orders (code) values (/* code */'A')\n");
        Files.writeString(dir.resolve("web/api/orders/names.sql"),
                "select code, name from codes where code in /* keys */('A')\n");
        Files.writeString(dir.resolve("web/api/orders/post.yml"), """
                version: tesseraql/v1
                id: orders.create
                kind: route
                recipe: command-json
                security:
                  auth: public
                steps:
                  - id: create
                    sql:
                      file: insert.sql
                      mode: update
                    enrich:
                      name:
                        on: { code: code }
                        sql:
                          file: names.sql
                        merge: [name]
                """);

        List<LintFinding> findings = new AppLinter().lint(dir);

        assertThat(findings)
                .filteredOn(f -> f.code().equals("TQL-FIELD-2009"))
                .singleElement()
                .matches(LintFinding::isError)
                .matches(f -> f.message().contains("'create'")
                        && f.message().contains("a command step writes"));
        // The step-shape checks keep TQL-FIELD-2004: this step's work is well formed.
        assertThat(findings).noneMatch(f -> f.code().equals("TQL-FIELD-2004"));
    }
}
