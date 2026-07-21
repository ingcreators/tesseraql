package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The {@code /* ${scope.name}/rel/path *}{@code / 'dummy'} file placeholder (docs/duckdb.md):
 * parser shape validation and renderer binding.
 */
class FilePathPlaceholderTest {

    private static final String SQL = "SELECT * FROM read_parquet(/* ${scope.sales}/monthly.parquet */ 'dummy.parquet')";

    @Test
    void parsesScopePlaceholderWithSuffix() {
        List<SqlNode> nodes = Sql2WayParser.parse(SQL);
        SqlNode.FilePath filePath = nodes.stream()
                .filter(SqlNode.FilePath.class::isInstance)
                .map(SqlNode.FilePath.class::cast)
                .findFirst()
                .orElseThrow();
        assertThat(filePath.channel()).isEqualTo("scope");
        assertThat(filePath.name()).isEqualTo("sales");
        assertThat(filePath.suffix()).isEqualTo("/monthly.parquet");
    }

    @Test
    void parsesDatasetPlaceholderWithoutSuffix() {
        List<SqlNode> nodes = Sql2WayParser
                .parse("SELECT * FROM read_parquet(/* ${dataset.report} */ 'dummy.parquet')");
        assertThat(nodes).anyMatch(node -> node instanceof SqlNode.FilePath filePath
                && "dataset".equals(filePath.channel()) && "report".equals(filePath.name())
                && filePath.suffix().isEmpty());
    }

    @Test
    void refusesTraversalUnknownChannelAndDatasetSuffix() {
        for (String bad : List.of(
                "/* ${scope.sales}/../secret.parquet */ 'd'",
                "/* ${scope.sales}/a\\\\b */ 'd'",
                "/* ${scope.sales}//double */ 'd'",
                "/* ${files.sales}/a */ 'd'",
                "/* ${scope.} */ 'd'",
                "/* ${dataset.report}/extra */ 'd'")) {
            assertThatThrownBy(() -> Sql2WayParser.parse("SELECT " + bad))
                    .as(bad)
                    .isInstanceOf(TqlException.class)
                    .hasMessageContaining("TQL-SQL-2102");
        }
    }

    @Test
    void rendersAsBoundParameterThroughTheResolver() {
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(SQL), Map.of(),
                ScopeResolver.UNSUPPORTED, Map.of(),
                (channel, name, suffix, context) -> "/data/" + name + suffix);
        assertThat(bound.sql()).contains("read_parquet(?)");
        assertThat(bound.parameters()).hasSize(1);
        assertThat(bound.parameters().get(0).value()).isEqualTo("/data/sales/monthly.parquet");
    }

    @Test
    void quotedStringsAreOpaqueToCommentScanning() {
        // Glob patterns and LIKE patterns legally carry /* inside string literals; '' escapes.
        List<SqlNode> nodes = Sql2WayParser.parse(
                "select * from glob('s3://x/**') where a like '%/*%' and b = 'it''s'");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0)).isInstanceOf(SqlNode.Text.class);

        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(
                "select /* q */ 'dummy' from t where u = 'a/*b'"), Map.of("q", "v"));
        assertThat(bound.sql()).contains("where u = 'a/*b'");
        assertThat(bound.parameters()).hasSize(1);

        assertThatThrownBy(() -> Sql2WayParser.parse("select 'unterminated"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unterminated string literal");
    }

    @Test
    void rejectsPlaceholderWithoutResolver() {
        assertThatThrownBy(() -> SqlRenderer.render(Sql2WayParser.parse(SQL), Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2111");
    }
}
