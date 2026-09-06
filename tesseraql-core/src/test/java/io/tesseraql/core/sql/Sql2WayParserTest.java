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
    void aConditionMayWrapOntoTheNextLine() {
        String sql = "select * from t where 1=1 /*%if\n  q != null\n  && q != ''\n*/"
                + " and name = /* q */ 'a' /*%end*/";

        assertThat(SqlRenderer.render(sql, Map.of("q", "x")).sql()).contains("and name = ?");
        assertThat(SqlRenderer.render(sql, Map.of()).sql()).doesNotContain("and name");
    }

    @Test
    void aTabAfterTheKeywordIsAccepted() {
        String sql = "select * from t where 1=1 /*%if\tq != null */"
                + " and name = /* q */ 'a' /*%end*/";

        assertThat(SqlRenderer.render(sql, Map.of("q", "x")).sql()).contains("and name = ?");
    }

    @Test
    void aForDirectiveMayWrapBeforeItsSeparator() {
        String sql = "insert into t (id) values /*%for id : ids\nseparator ', '*/"
                + " (/* id */ 0) /*%end*/";

        BoundSql bound = SqlRenderer.render(sql, Map.of("ids", List.of(1, 2)));

        assertThat(bound.sql()).contains("(?) ,  (?)");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly(1, 2);
    }

    @Test
    void elseifAfterElseIsRejected() {
        String sql = "select /*%if a != null */ x /*%else*/ y /*%elseif b != null */ z /*%end*/";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("can never run");
    }

    @Test
    void aSecondElseIsRejected() {
        String sql = "select /*%if a != null */ x /*%else*/ y /*%else*/ z /*%end*/";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("already has one");
    }

    @Test
    void anUnterminatedParenDummyIsRejected() {
        String sql = "select * from t where id in /* ids */ (1, 2";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("ids", List.of(1))))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("Unterminated dummy value group");
    }

    @Test
    void aLineCommentMarkerInsideAQuotedIdentifierDoesNotSwallowTheBind() {
        String sql = "select \"a--b\" from t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select \"a--b\" from t where id = ?");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly(7);
    }

    @Test
    void anApostropheInsideAQuotedAliasIsNotAStringLiteral() {
        String sql = "select x as \"Owner's name\" from t";

        BoundSql bound = SqlRenderer.render(sql, Map.of());

        assertThat(bound.sql()).isEqualTo(sql);
    }

    @Test
    void aCommentOpenerInsideAQuotedIdentifierIsNotADirective() {
        String sql = "select \"a/*b\" from t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select \"a/*b\" from t where id = ?");
        assertThat(bound.parameters()).extracting(BoundParameter::value).containsExactly(7);
    }

    @Test
    void aDoubledQuoteInsideAQuotedIdentifierIsPartOfIt() {
        String sql = "select \"a\"\"b\" from t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select \"a\"\"b\" from t where id = ?");
    }

    @Test
    void aBacktickedIdentifierIsOpaque() {
        String sql = "select `a/*b` from t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select `a/*b` from t where id = ?");
    }

    @Test
    void anUnterminatedQuotedIdentifierIsRejected() {
        String sql = "select \"abc from t";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("Unterminated quoted identifier");
    }

    @Test
    void aBracketIsPlainTextAndNotQuoting() {
        String sql = "select unnest([1, 2]) as n from t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select unnest([1, 2]) as n from t where id = ?");
    }

    @Test
    void anApostropheInsideALineCommentIsStillCommentText() {
        String sql = "select x -- don't\nfrom t where id = /* id */ 1";

        BoundSql bound = SqlRenderer.render(sql, Map.of("id", 7));

        assertThat(bound.sql()).isEqualTo("select x -- don't\nfrom t where id = ?");
    }
}
