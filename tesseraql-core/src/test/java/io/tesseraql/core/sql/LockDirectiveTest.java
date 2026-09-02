package io.tesseraql.core.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parsing and rendering of the lock directive (docs/edit-conflict.md decision 2). */
class LockDirectiveTest {

    private static final String UPDATE = "update items\n"
            + "   set name = /* name */ 'x',\n"
            + "       version = version + 1\n"
            + " where id = /* id */ 0\n"
            + "   and /*%lock*/ (1=1)";

    private static Map<String, Object> params(Object lock) {
        Map<String, Object> params = new HashMap<>();
        params.put("name", "Second item");
        params.put("id", 7L);
        params.put(LockBinding.PARAM, lock);
        return params;
    }

    @Test
    void rendersTheDeclaredColumnComparedAgainstTheSubmittedValue() {
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(UPDATE),
                params(new LockBinding("version", 3L, false)));

        assertThat(bound.sql()).contains("and (version = ?)");
        // The bind lands in directive position, after the statement's own binds.
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("Second item", 7L, 3L);
    }

    @Test
    void aWaivedLockRendersATautologyAndBindsNothing() {
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(UPDATE),
                params(new LockBinding("version", null, true)));

        assertThat(bound.sql()).contains("and (1=1)");
        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly("Second item", 7L);
    }

    @Test
    void parsesTheDirectiveWithItsSourceLine() {
        List<SqlNode> nodes = Sql2WayParser.parse("update t set a = 1\n where /*%lock*/ (1=1)");

        assertThat(nodes).anySatisfy(node -> {
            assertThat(node).isInstanceOf(SqlNode.Lock.class);
            assertThat(((SqlNode.Lock) node).sourceLine()).isEqualTo(2);
        });
    }

    @Test
    void lockDirectiveRequiresParenthesizedDummy() {
        assertThatThrownBy(() -> Sql2WayParser.parse("where /*%lock*/ true"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("parenthesized dummy");
    }

    @Test
    void lockDirectiveTakesNoArgument() {
        // The column is the route's lock: declaration; a second copy could disagree and nothing
        // could cross-check the two.
        assertThatThrownBy(() -> Sql2WayParser.parse("where /*%lock version */ (1=1)"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("takes no argument");
    }

    @Test
    void renderingAnUnseededLockFailsLoudlyRatherThanUnlockingTheWrite() {
        // The single most important assertion here: a fallback to (1=1) would be a silently
        // unlocked write, which is the defect the whole surface exists to abolish.
        List<SqlNode> nodes = Sql2WayParser.parse(UPDATE);
        Map<String, Object> unseeded = Map.of("name", "x", "id", 7L);

        assertThatThrownBy(() -> SqlRenderer.render(nodes, unseeded))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2115");
    }

    @Test
    void theSeededLockIsNotReachableAsAnAuthorBind() {
        // The renderer lifts it out of the bind scope, so /* _lock */ resolves to nothing —
        // the lock's version of the save-and-restore the scope directive performs.
        List<SqlNode> nodes = Sql2WayParser.parse("select /* _lock */ 0 from t");

        BoundSql bound = SqlRenderer.render(nodes, params(new LockBinding("version", 3L, false)));

        assertThat(bound.parameters()).extracting(BoundParameter::value)
                .containsExactly((Object) null);
    }

    @Test
    void aLockColumnMayBeAUnicodeIdentifier() {
        // The identifier contract (docs/unicode-identifiers.md): a Japanese column is a name
        // like any other.
        BoundSql bound = SqlRenderer.render(Sql2WayParser.parse(UPDATE),
                params(new LockBinding("版数", 3L, false)));

        assertThat(bound.sql()).contains("and (版数 = ?)");
    }

    @Test
    void aLockColumnThatIsNotAnIdentifierIsRefusedAtTheTextBoundary() {
        assertThatThrownBy(() -> new LockBinding("version = 1 or 1", 1L, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a SQL identifier");
    }
}
