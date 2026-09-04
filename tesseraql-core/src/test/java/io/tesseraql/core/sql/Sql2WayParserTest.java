package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parser's lexical layer: what a dummy value may contain, and where one ends. The feature
 * tests beside this one ({@link SqlRendererTest}, {@link ScopeDirectiveTest},
 * {@link LockDirectiveTest}, {@link FilePathPlaceholderTest}) each drive the layer through their
 * own directive; these cases drive it directly, because a scan that runs past the dummy takes the
 * rest of the statement with it and every one of those directives shares the scanner.
 */
class Sql2WayParserTest {

    @Test
    void emptyStringInAParenDummyDoesNotSwallowTheStatement() {
        String sql = "select * from t where code in /* codes */ ('') and active = 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("codes", List.of("A", "B")));

        assertThat(bound.sql()).isEqualTo("select * from t where code in (?, ?) and active = 1");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly("A", "B");
    }

    @Test
    void emptyStringAmongParenDummyElements() {
        String sql = "select * from t where code in /* codes */ ('A', '') and active = 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("codes", List.of("A", "B")));

        assertThat(bound.sql()).isEqualTo("select * from t where code in (?, ?) and active = 1");
    }

    @Test
    void aClosingParenInsideAQuotedElementDoesNotCloseTheGroup() {
        String sql = "select * from t where code in /* codes */ ('a)b') and active = 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("codes", List.of("A")));

        assertThat(bound.sql()).isEqualTo("select * from t where code in (?) and active = 1");
    }

    @Test
    void aRemarkInsideAParenDummyDoesNotOpenAQuotedRun() {
        String sql = "select * from t\nwhere id in /* ids */ (1, 2 -- don't count\n)\nand active = 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("ids", List.of(7)));

        assertThat(bound.sql()).contains("where id in (?)");
        assertThat(bound.sql()).contains("and active = 1");
    }

    @Test
    void anUnterminatedParenDummyIsRejected() {
        String sql = "select * from t where id in /* ids */ (1, 2";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("ids", List.of(1))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("Unterminated dummy value group");
    }
}
