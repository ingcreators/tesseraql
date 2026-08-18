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
            LintContext context = new LintContext(dir, findings, Set.of(),
                    io.tesseraql.core.expr.ExpressionFunctions.processDefault());

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
        LintContext context = new LintContext(dir, findings, Set.of(),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());

        assertThat(context.content(sql)).contains("select 1");
        // A differently spelled path to the same file hits the same memo entry.
        assertThat(context.sqlNodes(dir.resolve(".").resolve("query.sql"))).isNotEmpty();
        assertThat(findings).isEmpty();
    }

    @Test
    void malformedContentIsNullWithoutItsOwnFinding(@TempDir Path dir) throws Exception {
        // A document that does not parse is already reported where it loads; the context
        // must not double-report it.
        Path yml = dir.resolve("broken.yml");
        Files.writeString(yml, "a: [unclosed\n");
        Path sql = dir.resolve("broken.sql");
        Files.writeString(sql, "select /*%if body.x */ 1\n"); // unterminated directive
        List<LintFinding> findings = new ArrayList<>();
        LintContext context = new LintContext(dir, findings, Set.of(),
                io.tesseraql.core.expr.ExpressionFunctions.processDefault());

        assertThat(context.tree(yml)).isNull();
        assertThat(context.sqlNodes(sql)).isNull();
        assertThat(findings).isEmpty();
    }
}
