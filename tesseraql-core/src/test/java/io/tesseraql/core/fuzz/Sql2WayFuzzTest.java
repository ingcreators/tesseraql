package io.tesseraql.core.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.Sql2WayParser;
import io.tesseraql.core.sql.SqlNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fuzzes the 2-way SQL parser (docs/security-hardening.md): every input parses or raises a coded
 * {@link TqlException} — never a {@link StackOverflowError}, and never a hang. The regression cases
 * pin the specific deep-nesting defect the hardening fixed.
 */
class Sql2WayFuzzTest {

    private static final String[] TOKENS = {
            "/*%if a != null */", "/*%for i : xs */", "/*%scope s */", "/*%lock*/", "/*%end*/",
            "/*%elseif b */",
            "/*%else*/", "/* expr */", "/*# {x} */", "/* ${scope.s}/f */", "select", "from",
            "where",
            "'lit'", "'un", "(1=1)", "?", "/*", "*/", "--", "{", "}", "\n", " ", "()", "1",
    };
    private static final String[] CORPUS = {
            "select a from t where /*%if x != null */ a = /* x */ 1 /*%end*/",
            "insert into t (a) values (/*%for i : xs separator ',' */ /* i */ 1 /*%end*/)",
            "select /*# order by {sort} */ * from t",
            "select * from read_parquet(/* ${scope.s}/f.parquet */ 'd.parquet')",
            "select 'a''b' -- don't\nfrom t",
            "update t set a = /* a */ 1 where id = /* id */ 0 and /*%lock*/ (1=1)",
    };

    @Test
    @Timeout(30)
    void everyInputParsesOrIsCleanlyRejected() {
        ParserFuzz.fuzz("Sql2WayParser", Sql2WayParser::parse, TqlException.class,
                TOKENS, CORPUS, 20260722L, 4000);
    }

    /**
     * The oracle whose absence let a fail-open scan live from the initial engine commit: the
     * exception-type check above passes a parser that silently discards the rest of its input,
     * which is exactly what an unterminated dummy did. Every generated input gets a sentinel
     * clause appended; the parse must either refuse or keep it.
     *
     * <p>The sentinel is two tokens on purpose. If a trailing bind directive consumes it as a
     * bare-word dummy it eats only {@code select}, so the sentinel survives a legal parse.
     */
    @Test
    @Timeout(30)
    void noInputIsSilentlyTruncated() {
        for (String input : ParserFuzz.inputs(TOKENS, CORPUS, 20260904L, 4000)) {
            String probe = input + "\nselect zz_sentinel_zz, 1\n";
            List<SqlNode> nodes;
            try {
                nodes = Sql2WayParser.parse(probe);
            } catch (TqlException refused) {
                continue;
            }
            assertThat(nodes)
                    .withFailMessage("the parse kept neither the sentinel nor an error, on input:"
                            + "%n%s", ParserFuzz.describe(probe))
                    .anyMatch(node -> node instanceof SqlNode.Text text
                            && text.text().contains("zz_sentinel_zz"));
        }
    }

    /** The four inputs the oracle above catches at its seed, pinned so a reader can see them. */
    @Test
    void anUnterminatedDummyDoesNotEatWhatFollows() {
        assertThatThrownBy(() -> Sql2WayParser.parse(
                "select * from read_parquet(/* ${scope.s}/f.parquet*/ 'd.parquet)"))
                .isInstanceOf(TqlException.class);
        assertThatThrownBy(() -> Sql2WayParser.parse("select a = /* expr */ 'un\nselect zz, 1\n"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("Unterminated dummy value");
    }

    @Test
    void deepDirectiveNestingIsRejectedNotOverflowed() {
        String deep = "select 1 where " + "/*%if a != null */ ".repeat(5000) + "x=1 "
                + "/*%end*/ ".repeat(5000);
        assertThatThrownBy(() -> Sql2WayParser.parse(deep))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2102")
                .hasMessageContaining("nesting too deep");
    }

    @Test
    void realisticNestingStillParses() {
        String ok = "select 1 where " + "/*%if a != null */ ".repeat(20) + "x=1 "
                + "/*%end*/ ".repeat(20);
        assertThat(Sql2WayParser.parse(ok)).isNotEmpty();
    }
}
