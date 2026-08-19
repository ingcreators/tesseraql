package io.tesseraql.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.time.Duration;
import java.util.List;
import org.apache.cxf.rs.security.oauth2.common.AccessTokenRegistration;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.ServerAccessToken;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.refresh.RefreshTokenGrantHandler;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CXF's real {@code RefreshTokenGrantHandler} driven against the provider: rotation on every
 * use, and reuse detection that retires the whole chain (stack-architecture.md decision 9).
 */
class RefreshRotationTest {

    private static final String RESOURCE = "https://stack.example.com/orders";

    private final InMemoryOAuthStore store = new InMemoryOAuthStore();
    private final CapturingSigner signer = new CapturingSigner();
    private final MutableClock clock = new MutableClock();
    private final TesseraqlOAuthDataProvider provider = new TesseraqlOAuthDataProvider(store,
            signer, clock);
    private final RefreshTokenGrantHandler handler = new RefreshTokenGrantHandler();

    private Client client;

    @BeforeEach
    void setUp() {
        handler.setDataProvider(provider);
        store.saveClient(new RegisteredClient("codex", null,
                List.of("http://127.0.0.1:49681/callback/x"), "Codex CLI", null,
                clock.instant(), null));
        client = provider.getClient("codex");
    }

    @Test
    void aRefreshRotatesTheTokenAndKeepsTheCapacity() {
        ServerAccessToken first = mintInitial("approver");

        ServerAccessToken second = refresh(first.getRefreshToken());

        assertThat(second.getRefreshToken()).isNotEqualTo(first.getRefreshToken());
        assertThat(signer.lastClaims())
                .containsEntry("aud", RESOURCE)
                .containsEntry("acting_role", "approver");
    }

    @Test
    void aSpentTokenPresentedAgainRetiresTheWholeChain() {
        ServerAccessToken first = mintInitial(null);
        ServerAccessToken second = refresh(first.getRefreshToken());

        // The spent token again: reuse detected.
        assertThatThrownBy(() -> refresh(first.getRefreshToken()))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
        // And the reuse retired the live end of the chain too.
        assertThatThrownBy(() -> refresh(second.getRefreshToken()))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void revocationKillsTheChain() {
        ServerAccessToken token = mintInitial(null);

        provider.revokeToken(client, token.getRefreshToken(), null);

        assertThatThrownBy(() -> refresh(token.getRefreshToken()))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void anExpiredRefreshTokenIsRefused() {
        ServerAccessToken token = mintInitial(null);

        clock.advance(TesseraqlOAuthDataProvider.DEFAULT_REFRESH_TOKEN_LIFETIME
                .plus(Duration.ofDays(1)));

        assertThatThrownBy(() -> refresh(token.getRefreshToken()))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void aForeignClientCannotSpendTheToken() {
        ServerAccessToken token = mintInitial(null);
        store.saveClient(new RegisteredClient("impostor", null,
                List.of("http://127.0.0.1:50000/callback/y"), "Impostor", null,
                clock.instant(), null));
        Client impostor = provider.getClient("impostor");

        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(OAuthConstants.REFRESH_TOKEN, token.getRefreshToken());

        assertThatThrownBy(() -> handler.createAccessToken(impostor, params))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void theSubjectFacingListingShowsOnlyLiveTokens() {
        ServerAccessToken first = mintInitial(null);
        refresh(first.getRefreshToken());

        var listed = provider.getRefreshTokens(client, new UserSubject("eve", "u-1"));

        // One live link per chain: the spent link is rotation history, not an authorisation.
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).getAudiences()).containsExactly(RESOURCE);
    }

    private ServerAccessToken mintInitial(String actingRole) {
        AccessTokenRegistration reg = new AccessTokenRegistration();
        reg.setClient(client);
        UserSubject subject = new UserSubject("eve", "u-1");
        if (actingRole != null) {
            subject.getProperties().put(TesseraqlOAuthDataProvider.ACTING_ROLE, actingRole);
        }
        reg.setSubject(subject);
        reg.setAudiences(List.of(RESOURCE));
        reg.setGrantType(OAuthConstants.AUTHORIZATION_CODE_GRANT);
        return provider.createAccessToken(reg);
    }

    private ServerAccessToken refresh(String refreshToken) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(OAuthConstants.REFRESH_TOKEN, refreshToken);
        return handler.createAccessToken(client, params);
    }
}
