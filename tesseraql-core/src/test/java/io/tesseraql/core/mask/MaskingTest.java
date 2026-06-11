package io.tesseraql.core.mask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void emailStrategy() {
        assertThat(Masking.apply("email", "sato@example.com")).isEqualTo("s***@example.com");
        assertThat(Masking.apply("email", "no-at")).isEqualTo(Masking.FIXED);
    }

    @Test
    void last4Strategy() {
        assertThat(Masking.apply("last4", "4111111111111234")).isEqualTo("****1234");
        assertThat(Masking.apply("last4", "12")).isEqualTo("****");
    }

    @Test
    void fixedAndUnknownFallBackToFixed() {
        assertThat(Masking.apply("fixed", "secret")).isEqualTo(Masking.FIXED);
        assertThat(Masking.apply("unknown", "secret")).isEqualTo(Masking.FIXED);
    }

    @Test
    void nullStaysNull() {
        assertThat(Masking.apply("email", null)).isNull();
    }

    @Test
    void classificationDefaults() {
        assertThat(Masking.defaultActionFor("credential")).isEqualTo("hide");
        assertThat(Masking.defaultActionFor("pii")).isEqualTo("mask");
        assertThat(Masking.defaultActionFor("public")).isNull();
        assertThat(Masking.defaultActionFor(null)).isNull();
    }
}
