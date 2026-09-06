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
    void anUnterminatedDummyDoesNotSwallowTheStatement() {
        // The scalar half of the same fail-open #1148 closed for the paren group: skipQuoted
        // returned silently at end of input, so everything after the opening quote — a tenant
        // guard among it — was scanned away as part of the dummy.
        String sql = "select * from t\nwhere q = /* q */ 'oops\nand tenant_id = 7";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("q", "x")))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("Unterminated dummy value");
    }

    @Test
    void anEscapedQuoteInsideADummyIsPartOfIt() {
        String sql = "select * from t where name = /* name */ 'O''Brien'";

        assertThat(SqlRenderer.render(sql, Map.of("name", "x")).sql())
                .isEqualTo("select * from t where name = ?");
    }

    @Test
    void aCharsetPrefixedLiteralIsOneDummy() {
        String sql = "select * from t where name = /* name */ N'山田'";

        assertThat(SqlRenderer.render(sql, Map.of("name", "x")).sql())
                .isEqualTo("select * from t where name = ?");
    }

    @Test
    void aTypedLiteralIsOneDummy() {
        String sql = "select * from t where d = /* d */ DATE '2024-01-01'";

        assertThat(SqlRenderer.render(sql, Map.of("d", "x")).sql())
                .isEqualTo("select * from t where d = ?");
    }

    @Test
    void aCallDummyIsOneDummy() {
        String sql = "select * from t where at = /* at */ now()";

        assertThat(SqlRenderer.render(sql, Map.of("at", "x")).sql())
                .isEqualTo("select * from t where at = ?");
    }

    @Test
    void aHexNumberIsOneDummy() {
        String sql = "select * from t where b = /* b */ 0x1F";

        assertThat(SqlRenderer.render(sql, Map.of("b", 1)).sql())
                .isEqualTo("select * from t where b = ?");
    }

    @Test
    void anExponentIsPartOfTheNumber() {
        String sql = "select * from t where r = /* r */ -1.5e-3";

        assertThat(SqlRenderer.render(sql, Map.of("r", 1)).sql())
                .isEqualTo("select * from t where r = ?");
    }

    @Test
    void aQuotedWordAfterANonTypeDummyIsLeftAlone() {
        // The type-keyword whitelist boundary: `x` is not a type, so the quoted run beside it is
        // an alias the statement keeps, not part of the dummy.
        String sql = "select /* x */ x 'alias' from t";

        assertThat(SqlRenderer.render(sql, Map.of("x", 1)).sql())
                .isEqualTo("select ? 'alias' from t");
    }

    @Test
    void aBindWithNoDummyIsRejected() {
        // Not cosmetic: the bare-word scanner eats the next keyword as the dummy, so this
        // rendered `select ?, ? t` today — a statement the author never wrote.
        String sql = "select /* a */, /* b */ from t";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of("a", 1, "b", 2)))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("must be followed by a dummy value");
    }

    @Test
    void aMalformedExpressionIsDiagnosedBeforeTheMissingDummy() {
        // Studio's SQL builder emits `insert into <t> (/* TODO: columns */)` as an
        // author-fills-this-in placeholder. The expression is the wrong half to complain about
        // twice, so the expression diagnostic still outranks the dummy refusal.
        String sql = "insert into t (/* TODO: columns */)";

        assertThatThrownBy(() -> SqlRenderer.render(sql, Map.of()))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101");
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
