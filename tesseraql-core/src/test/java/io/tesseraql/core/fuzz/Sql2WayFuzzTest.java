package io.tesseraql.core.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.sql.Sql2WayParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fuzzes the 2-way SQL parser (docs/security-hardening.md): every input parses or raises a coded
 * {@link TqlException} — never a {@link StackOverflowError}, and never a hang. The regression cases
 * pin the specific deep-nesting defect the hardening fixed.
 */
class Sql2WayFuzzTest {

    private static final String[] TOKENS = {
            "/*%if a != null */", "/*%for i : xs */", "/*%scope s */", "/*%end*/", "/*%elseif b */",
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
    };

    @Test
    @Timeout(30)
    void everyInputParsesOrIsCleanlyRejected() {
        ParserFuzz.fuzz("Sql2WayParser", Sql2WayParser::parse, TqlException.class,
                TOKENS, CORPUS, 20260722L, 4000);
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
