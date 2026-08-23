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
}
