package io.tesseraql.yaml.lint;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LintContextTest {

    @Test
    void anUnreadableFileIsOneFindingAndNullContent(@TempDir Path dir) throws Exception {
        // The manifest loader hashes every source file, so a file unreadable from the start
        // fails the load before lint runs; this covers the mid-run window, where each rule
        // used to lint quietly against empty content instead.
        Path sql = dir.resolve("update.sql");
        Files.writeString(sql, "update t set a = 1\n");
        Assumptions.assumeTrue(
                sql.getFileSystem().supportedFileAttributeViews().contains("posix"));
        Files.setPosixFilePermissions(sql, Set.of());
        // Root (some CI sandboxes) reads through 000 permissions; nothing to test then.
        Assumptions.assumeFalse(Files.isReadable(sql));
        try {
            List<LintFinding> findings = new ArrayList<>();
            LintContext context = new LintContext(dir, findings, Set.of(), Set.of(),
                    io.tesseraql.core.expr.ExpressionFunctions.processDefault(),
                    io.tesseraql.core.files.FileCodecs.of());

            assertThat(context.content(sql)).isNull();
            // Every reader of the same file answers null off the memo, and the failure
            // surfaces once per file — not once per rule that tried to read it.
            assertThat(context.content(sql)).isNull();
            assertThat(context.tree(sql)).isNull();
            assertThat(context.sqlNodes(sql)).isNull();
            assertThat(findings).singleElement().satisfies(finding -> {
                assertThat(finding.code()).isEqualTo("TQL-YAML-1053");
                assertThat(finding.severity()).isEqualTo("warning");
                assertThat(finding.source()).isEqualTo("update.sql");
            });
        } finally {
            Files.setPosixFilePermissions(sql, PosixFilePermissions.fromString("rw-r--r--"));
        }
    }

    @Test
    void readableContentIsMemoizedWithoutFindings(@TempDir Path dir) throws Exception {
        Path sql = dir.resolve("query.sql");
        Files.writeString(sql, "select 1 where id = /* body.id */1\n");
        List<LintFinding> findings = new ArrayList<>();
        LintContext context = new LintContext(dir, findings, Set.of(), Set.of(),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault(),
                io.tesseraql.core.files.FileCodecs.of());

        assertThat(context.content(sql)).contains("select 1");
        // A differently spelled path to the same file hits the same memo entry.
        assertThat(context.sqlNodes(dir.resolve(".").resolve("query.sql"))).isNotEmpty();
        assertThat(findings).isEmpty();
    }

    @Test
    void aMalformedYamlDocumentIsNullWithoutItsOwnFinding(@TempDir Path dir) throws Exception {
        // A YAML document that does not parse is already reported where it loads; the context
        // must not double-report it.
        Path yml = dir.resolve("broken.yml");
        Files.writeString(yml, "a: [unclosed\n");
        List<LintFinding> findings = new ArrayList<>();
        LintContext context = new LintContext(dir, findings, Set.of(), Set.of(),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault(),
                io.tesseraql.core.files.FileCodecs.of());

        assertThat(context.tree(yml)).isNull();
        assertThat(findings).isEmpty();
    }

    @Test
    void anUnparseableSqlFileIsOneFindingWithTheParsersCodeAndLine(@TempDir Path dir)
            throws Exception {
        // Nothing loads a query route's SQL before its first request, so this memo is the
        // only static gate that sees the failure (docs/audit-low-leads.md G9). It used to
        // answer null on the premise that another lint owned the SQL; none did.
        Path sql = dir.resolve("broken.sql");
        Files.writeString(sql, "select 1\n/*%if body.x */ 1\n"); // unterminated directive
        Path directive = dir.resolve("directive.sql");
        Files.writeString(directive, "select 1\nwhere 1 = 1\n/*%if q > */ and x = 1 /*%end*/\n");
        List<LintFinding> findings = new ArrayList<>();
        LintContext context = new LintContext(dir, findings, Set.of(), Set.of(),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault(),
                io.tesseraql.core.files.FileCodecs.of());

        assertThat(context.sqlNodes(sql)).isNull();
        // Every rule that asks answers null off the memo; the failure surfaces once per file.
        assertThat(context.sqlNodes(dir.resolve(".").resolve("broken.sql"))).isNull();
        assertThat(context.sqlNodes(directive)).isNull();
        assertThat(findings).hasSize(2);
        assertThat(findings.get(0)).satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SQL-2102");
            assertThat(finding.severity()).isEqualTo("error");
            assertThat(finding.source()).isEqualTo("broken.sql");
            assertThat(finding.line()).isNotNull(); // the parser names the line it stopped at
            assertThat(finding.message()).contains("does not parse");
        });
        assertThat(findings.get(1)).satisfies(finding -> {
            assertThat(finding.code()).isEqualTo("TQL-SQL-2101");
            assertThat(finding.source()).isEqualTo("directive.sql");
            assertThat(finding.line()).isEqualTo(3);
            assertThat(finding.message()).contains("Unexpected end of expression in 'q >'");
        });
    }
}
