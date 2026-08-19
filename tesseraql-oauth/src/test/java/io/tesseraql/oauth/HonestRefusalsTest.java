package io.tesseraql.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.junit.jupiter.api.Test;

/**
 * The twelve-method contract's unexercised remainder (docs/token-issuance.md decision 2):
 * honest refusals with a recorded reason, a null that means "mint a new one", and a scope
 * conversion that grants nothing.
 */
class HonestRefusalsTest {

    private final TesseraqlOAuthDataProvider provider = new TesseraqlOAuthDataProvider(
            new InMemoryOAuthStore(), new CapturingSigner(), new MutableClock());

    @Test
    void statelessAccessTokensAnswerNoLookup() {
        assertThatThrownBy(() -> provider.getAccessToken("any"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("stateless");
        assertThatThrownBy(() -> provider.getAccessTokens(null, new UserSubject("eve", "u-1")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("stateless");
    }

    @Test
    void hashedCodesAnswerNoEnumeration() {
        assertThatThrownBy(() -> provider.getCodeGrants(null, new UserSubject("eve", "u-1")))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("single-use");
    }

    @Test
    void noPreauthorizedTokenExistsAndThatIsTheOrdinaryAnswer() {
        assertThat(provider.getPreauthorizedToken(null, List.of(),
                new UserSubject("eve", "u-1"), "authorization_code")).isNull();
    }

    @Test
    void scopeConvertsToNothing() {
        assertThat(provider.convertScopeToPermissions(null, List.of("read", "write")))
                .isEmpty();
    }
}
