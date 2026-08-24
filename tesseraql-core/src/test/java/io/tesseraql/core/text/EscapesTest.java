package io.tesseraql.core.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EscapesTest {

    /** The quote is part of the contract: dropping it was the live divergence. */
    @Test
    void htmlEscapesTheAttributeBreakingFour() {
        assertThat(Escapes.html("a<b>&\"c\"")).isEqualTo("a&lt;b&gt;&amp;&quot;c&quot;");
    }

    @Test
    void xmlTextLeavesQuotesAndXmlAttributeDoesNot() {
        assertThat(Escapes.xmlText("\"a\" & <b>")).isEqualTo("\"a\" &amp; &lt;b&gt;");
        assertThat(Escapes.xmlAttribute("\"a\" & <b>"))
                .isEqualTo("&quot;a&quot; &amp; &lt;b&gt;");
    }

    /** The ampersand goes first, so entities are never double-escaped. */
    @Test
    void escapingIsNotAppliedTwiceToItsOwnEntities() {
        assertThat(Escapes.html("&lt;")).isEqualTo("&amp;lt;");
    }

    /**
     * The single quote stays: no framework writer emits single-quoted attributes, and the one
     * OGNL-literal writer escapes for its own grammar first (docs pin, not a contract).
     */
    @Test
    void htmlLeavesTheSingleQuoteAlone() {
        assertThat(Escapes.html("it's")).isEqualTo("it's");
        assertThat(Escapes.xmlAttribute("it's")).isEqualTo("it's");
    }

    /**
     * XML 1.0 cannot carry the C0 controls (save tab, LF, CR) even as character references —
     * an exception message holding one used to turn the whole JUnit report invalid.
     */
    @Test
    void xmlIllegalControlCharactersBecomeTheReplacementCharacter() {
        assertThat(Escapes.xmlText("a\u0000b\u001fc")).isEqualTo("a\uFFFDb\uFFFDc");
        assertThat(Escapes.xmlAttribute("a\u0000\"b")).isEqualTo("a\uFFFD&quot;b");
        assertThat(Escapes.xmlText("tab\tnl\ncr\r")).isEqualTo("tab\tnl\ncr\r");
    }
}
