package io.tesseraql.oidc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** PKCE S256 against the RFC 7636 Appendix B test vector, plus token shape. */
class PkceTest {

    @Test
    void s256ChallengeMatchesRfc7636Vector() {
        String verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        assertThat(Pkce.challenge(verifier))
                .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    void tokensAreUrlSafeAndUnique() {
        String a = Pkce.token();
        String b = Pkce.token();
        assertThat(a).hasSize(43).matches("[A-Za-z0-9_-]+");
        assertThat(a).isNotEqualTo(b);
        assertThat(Pkce.verifier()).hasSize(43);
    }
}
