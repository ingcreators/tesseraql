package io.tesseraql.core.fuzz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.ExpressionParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fuzzes the directive expression parser (docs/security-hardening.md): every input parses or raises
 * a coded {@link TqlException}, never a {@link StackOverflowError}. The regression cases pin the
 * deep-grouping and unary-chain overflows the depth guard fixed.
 */
class ExpressionParserFuzzTest {

    private static final String[] TOKENS = {
            "(", ")", "!", "-", "+", "*", "/", "%", "&&", "||", "==", "!=", "<", ">", "<=", ">=",
            "a", "b.c", "null", "true", "false", "1", "2.5", "'s'", "fn(", ",", " ", "d.e.f",
    };
    private static final String[] CORPUS = {
            "a != null && b.c == 'x'",
            "!(a || b) && (c > 1 - 2 * 3)",
            "isKatakana(body.kana) || count > 0",
            "-a + b.c.d.e",
            "((a))",
    };

    @Test
    @Timeout(30)
    void everyInputParsesOrIsCleanlyRejected() {
        ParserFuzz.fuzz("ExpressionParser", ExpressionParser::parse, TqlException.class,
                TOKENS, CORPUS, 20260722L, 4000);
    }

    @Test
    void deepGroupingIsRejectedNotOverflowed() {
        String deep = "(".repeat(5000) + "a" + ")".repeat(5000);
        assertThatThrownBy(() -> ExpressionParser.parse(deep))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("TQL-SQL-2101")
                .hasMessageContaining("nesting too deep");
    }

    @Test
    void deepUnaryChainIsRejectedNotOverflowed() {
        assertThatThrownBy(() -> ExpressionParser.parse("!".repeat(5000) + "a"))
                .isInstanceOf(TqlException.class)
                .hasMessageContaining("nesting too deep");
    }

    @Test
    void realisticExpressionsStillParse() {
        assertThat(ExpressionParser.parse("!(a && b) || (c.d > 1)")).isNotNull();
        assertThat(ExpressionParser.parse("((((a))))")).isNotNull();
    }
}
