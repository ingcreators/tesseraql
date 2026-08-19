package io.tesseraql.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.ServerAccessToken;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeGrantHandler;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeRegistration;
import org.apache.cxf.rs.security.oauth2.grants.code.DigestCodeVerifier;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CXF's real {@code AuthorizationCodeGrantHandler} driven against the twelve-method provider
 * (docs/token-issuance.md decision 2): single-use codes, S256-only PKCE, and claims minted for
 * the granted resource — proven with the grant layer alone on the classpath, no JAX-RS runtime.
 */
class AuthorizationCodeFlowTest {

    private static final String REDIRECT = "http://127.0.0.1:49681/callback/gSuWNlcOrmWI";
    private static final String RESOURCE = "https://stack.example.com/orders";
    private static final String VERIFIER = "correct-horse-battery-staple-correct-horse-battery";

    private final InMemoryOAuthStore store = new InMemoryOAuthStore();
    private final CapturingSigner signer = new CapturingSigner();
    private final MutableClock clock = new MutableClock();
    private final TesseraqlOAuthDataProvider provider = new TesseraqlOAuthDataProvider(store,
            signer, clock);
    private final AuthorizationCodeGrantHandler handler = new AuthorizationCodeGrantHandler();

    private Client client;

    @BeforeEach
    void setUp() {
        handler.setDataProvider(provider);
        handler.setCanSupportPublicClients(true);
        // Every client presents a verifier, and only S256 is an accepted method: the sole
        // registered transformer refuses "plain" rather than downgrading to it (decision 4).
        handler.setRequireCodeVerifier(true);
        handler.setCodeVerifierTransformers(List.of(new DigestCodeVerifier()));
        store.saveClient(new RegisteredClient("codex", null, List.of(REDIRECT), "Codex CLI",
                null, clock.instant(), null));
        client = provider.getClient("codex");
    }

    @Test
    void theCodeFlowMintsForTheGrantedResourceAndCapacity() {
        String code = issueCode(challenge(VERIFIER), "S256", "approver");

        ServerAccessToken token = redeem(code, VERIFIER);

        assertThat(token).isNotNull();
        assertThat(token.getTokenKey()).isEqualTo("signed-1");
        assertThat(token.getRefreshToken()).isNotNull();
        assertThat(signer.lastClaims())
                .containsEntry("sub", "u-1")
                .containsEntry("login", "eve")
                .containsEntry("aud", RESOURCE)
                .containsEntry("acting_role", "approver")
                .containsKeys("iat", "exp", "jti");
    }

    @Test
    void aCodeIsSingleUse() {
        String code = issueCode(challenge(VERIFIER), "S256", null);

        assertThat(redeem(code, VERIFIER)).isNotNull();
        assertThat(redeem(code, VERIFIER)).isNull();
    }

    @Test
    void aWrongVerifierIsRefused() {
        String code = issueCode(challenge(VERIFIER), "S256", null);

        assertThatThrownBy(() -> redeem(code, "some-other-verifier-of-sufficient-length-here"))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void aMissingVerifierIsRefused() {
        String code = issueCode(null, null, null);

        assertThatThrownBy(() -> redeem(code, null))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void thePlainMethodIsRefusedRatherThanDowngradedTo() {
        // A "plain" registration stores the verifier itself as the challenge; redemption still
        // compares through S256 only, so the plain semantics never verify.
        String code = issueCode(VERIFIER, "plain", null);

        assertThatThrownBy(() -> redeem(code, VERIFIER))
                .isInstanceOf(OAuthServiceException.class)
                .hasMessageContaining(OAuthConstants.INVALID_GRANT);
    }

    @Test
    void anExpiredCodeIsDead() {
        String code = issueCode(challenge(VERIFIER), "S256", null);

        clock.advance(Duration.ofMinutes(3));

        assertThat(redeem(code, VERIFIER)).isNull();
    }

    @Test
    void aForeignRedirectUriIsRefused() {
        String code = issueCode(challenge(VERIFIER), "S256", null);
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(OAuthConstants.AUTHORIZATION_CODE_VALUE, code);
        params.putSingle(OAuthConstants.AUTHORIZATION_CODE_VERIFIER, VERIFIER);
        params.putSingle(OAuthConstants.REDIRECT_URI, "http://127.0.0.1:50000/elsewhere");

        assertThatThrownBy(() -> handler.createAccessToken(client, params))
                .isInstanceOf(OAuthServiceException.class);
    }

    @Test
    void consentRidesTheStorePerClientAndResource() {
        Instant now = clock.instant();
        store.saveConsent(new RecordedConsent("codex", "u-1", RESOURCE, "approver", now));

        assertThat(store.findConsent("codex", "u-1", RESOURCE))
                .hasValueSatisfying(
                        consent -> assertThat(consent.actingRole()).isEqualTo("approver"));
        // Consenting to one application in a stack is not consenting to the rest.
        assertThat(store.findConsent("codex", "u-1", "https://stack.example.com/billing"))
                .isEmpty();
    }

    private String issueCode(String codeChallenge, String method, String actingRole) {
        AuthorizationCodeRegistration reg = new AuthorizationCodeRegistration();
        reg.setClient(client);
        reg.setRedirectUri(REDIRECT);
        UserSubject subject = new UserSubject("eve", "u-1");
        if (actingRole != null) {
            subject.getProperties().put(TesseraqlOAuthDataProvider.ACTING_ROLE, actingRole);
        }
        reg.setSubject(subject);
        reg.setAudience(RESOURCE);
        reg.setClientCodeChallenge(codeChallenge);
        reg.setClientCodeChallengeMethod(method);
        ServerAuthorizationCodeGrant grant = provider.createCodeGrant(reg);
        assertThat(grant.getCode()).isNotNull();
        return grant.getCode();
    }

    private ServerAccessToken redeem(String code, String verifier) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(OAuthConstants.AUTHORIZATION_CODE_VALUE, code);
        if (verifier != null) {
            params.putSingle(OAuthConstants.AUTHORIZATION_CODE_VERIFIER, verifier);
        }
        params.putSingle(OAuthConstants.REDIRECT_URI, REDIRECT);
        return handler.createAccessToken(client, params);
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
