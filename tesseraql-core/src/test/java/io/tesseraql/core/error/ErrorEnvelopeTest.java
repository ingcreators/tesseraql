package io.tesseraql.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorEnvelopeTest {

    @Test
    void spellsTheFrameworkEnvelope() {
        assertThat(ErrorEnvelope.json(new TqlErrorCode(TqlDomain.ROUTE, 5000), "Internal error"))
                .isEqualTo("{\"error\":{\"code\":\"TQL-ROUTE-5000\","
                        + "\"message\":\"Internal error\"}}");
    }

    /** The strings that break naive concatenation stay valid JSON here. */
    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        assertThat(ErrorEnvelope.json(new TqlErrorCode(TqlDomain.APP, 4030),
                "a \"quoted\" \\ phrase\nnext"))
                .isEqualTo("{\"error\":{\"code\":\"TQL-APP-4030\","
                        + "\"message\":\"a \\\"quoted\\\" \\\\ phrase\\u000anext\"}}");
    }
}
