package io.tesseraql.oauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.cxf.rs.security.oauth2.common.AccessTokenRegistration;
import org.apache.cxf.rs.security.oauth2.common.Client;
import org.apache.cxf.rs.security.oauth2.common.OAuthPermission;
import org.apache.cxf.rs.security.oauth2.common.ServerAccessToken;
import org.apache.cxf.rs.security.oauth2.common.UserSubject;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeDataProvider;
import org.apache.cxf.rs.security.oauth2.grants.code.AuthorizationCodeRegistration;
import org.apache.cxf.rs.security.oauth2.grants.code.ServerAuthorizationCodeGrant;
import org.apache.cxf.rs.security.oauth2.provider.OAuthServiceException;
import org.apache.cxf.rs.security.oauth2.tokens.bearer.BearerAccessToken;
import org.apache.cxf.rs.security.oauth2.tokens.refresh.RefreshToken;
import org.apache.cxf.rs.security.oauth2.utils.OAuthConstants;

/**
 * The twelve-method data provider beneath CXF's {@code code} and {@code refresh} grant handlers
 * (docs/token-issuance.md decision 2): codes, refresh tokens, clients and consents are stored;
 * access tokens are minted through the {@link AccessTokenSigner} seam and never stored. The
 * interface is implemented directly rather than by extending CXF's abstract providers — the
 * shipped storage and rotation logic is the part carrying the 2026 advisory record, and
 * replacing it is the structural half of the mitigation decision 1 promised.
 *
 * <p>Four methods are ever exercised by the grant handlers; the rest are storage-backed where
 * the store genuinely answers them, and honest refusals with the reason where it does not.
 */
public final class TesseraqlOAuthDataProvider implements AuthorizationCodeDataProvider {

    /** Codes are one redirect long; two minutes absorbs a slow consent screen, nothing more. */
    static final Duration CODE_LIFETIME = Duration.ofMinutes(2);

    /**
     * Placeholder lifetimes (docs/token-issuance.md open question 8): shipped as defaults,
     * named in the operator documentation with the /token slice, tuned against measured client
     * refresh behavior once the observe pass runs against a live stack.
     */
    public static final Duration DEFAULT_ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(15);
    public static final Duration DEFAULT_REFRESH_TOKEN_LIFETIME = Duration.ofDays(30);

    /** The subject property carrying a selected acting role through grant and refresh. */
    public static final String ACTING_ROLE = "acting_role";

    /**
     * The grant property carrying the RFC 8707 resource. It rides the grant's extra properties
     * rather than CXF's audience field, because the handlers validate an audience against the
     * client's <em>registered</em> audiences — and a DCR client registers none; the resource is
     * per-request. Which resources a subject may reach is the authorize endpoint's question,
     * answered before any grant is created, not the client registration's.
     */
    public static final String RESOURCE = "resource";

    private final OAuthStore store;
    private final AccessTokenSigner signer;
    private final Clock clock;
    private final Duration accessTokenLifetime;
    private final Duration refreshTokenLifetime;

    public TesseraqlOAuthDataProvider(OAuthStore store, AccessTokenSigner signer, Clock clock) {
        this(store, signer, clock, DEFAULT_ACCESS_TOKEN_LIFETIME, DEFAULT_REFRESH_TOKEN_LIFETIME);
    }

    public TesseraqlOAuthDataProvider(OAuthStore store, AccessTokenSigner signer, Clock clock,
            Duration accessTokenLifetime, Duration refreshTokenLifetime) {
        this.store = store;
        this.signer = signer;
        this.clock = clock;
        this.accessTokenLifetime = accessTokenLifetime;
        this.refreshTokenLifetime = refreshTokenLifetime;
    }

    @Override
    public Client getClient(String clientId) {
        Optional<RegisteredClient> registered = store.findClient(clientId);
        if (registered.isEmpty()) {
            return null;
        }
        store.touchClient(clientId, clock.instant());
        RegisteredClient r = registered.get();
        Client client = new Client(r.clientId(), null, r.secretHash() != null);
        client.setRedirectUris(new ArrayList<>(r.redirectUris()));
        client.setAllowedGrantTypes(List.of(
                OAuthConstants.AUTHORIZATION_CODE_GRANT, OAuthConstants.REFRESH_TOKEN_GRANT));
        client.setApplicationName(r.clientName());
        return client;
    }

    @Override
    public ServerAuthorizationCodeGrant createCodeGrant(AuthorizationCodeRegistration reg) {
        String code = Tokens.newToken();
        Instant now = clock.instant();
        ServerAuthorizationCodeGrant grant = new ServerAuthorizationCodeGrant(
                reg.getClient(), code, CODE_LIFETIME.toSeconds(), now.getEpochSecond());
        grant.setSubject(reg.getSubject());
        grant.setRedirectUri(reg.getRedirectUri());
        if (reg.getAudience() != null) {
            grant.getExtraProperties().put(RESOURCE, reg.getAudience());
        }
        grant.setClientCodeChallenge(reg.getClientCodeChallenge());
        grant.setClientCodeChallengeMethod(reg.getClientCodeChallengeMethod());
        grant.setPreauthorizedTokenAvailable(false);
        store.saveCode(new IssuedCode(
                Tokens.sha256Hex(code),
                reg.getClient().getClientId(),
                subjectId(reg.getSubject()),
                login(reg.getSubject()),
                reg.getAudience(),
                actingRole(reg.getSubject()),
                reg.getClientCodeChallenge(),
                reg.getRedirectUri(),
                now.plus(CODE_LIFETIME)));
        return grant;
    }

    @Override
    public ServerAuthorizationCodeGrant removeCodeGrant(String code) {
        if (code == null) {
            return null;
        }
        // Expiry is answered here, against this provider's clock, rather than left to the
        // handler's wall-clock check — and the consume has already retired the row either way.
        return store.consumeCode(Tokens.sha256Hex(code))
                .filter(stored -> stored.expiresAt().isAfter(clock.instant()))
                .map(this::reconstruct)
                .orElse(null);
    }

    /**
     * Enumeration is refused: the store keys codes by one-way hash, so there is nothing to list
     * that a caller could redeem — and no endpoint this server builds wants the listing.
     */
    @Override
    public List<ServerAuthorizationCodeGrant> getCodeGrants(Client client, UserSubject subject) {
        throw new UnsupportedOperationException(
                "codes are stored one-way and single-use (docs/token-issuance.md decision 2)");
    }

    @Override
    public ServerAccessToken createAccessToken(AccessTokenRegistration reg) {
        String audience = reg.getAudiences().isEmpty()
                ? reg.getExtraProperties().get(RESOURCE)
                : reg.getAudiences().get(0);
        return mint(reg.getClient(), reg.getSubject(), audience, actingRole(reg.getSubject()));
    }

    @Override
    public ServerAccessToken refreshAccessToken(Client client, String refreshTokenKey,
            List<String> restrictedScopes) {
        Instant now = clock.instant();
        String hash = Tokens.sha256Hex(refreshTokenKey);
        IssuedRefreshToken stored = store.findRefreshToken(hash)
                .orElseThrow(() -> new OAuthServiceException(OAuthConstants.INVALID_GRANT));
        if (stored.revokedAt() != null || !stored.clientId().equals(client.getClientId())
                || stored.expiresAt().isBefore(now)) {
            throw new OAuthServiceException(OAuthConstants.INVALID_GRANT);
        }
        // A rotated token presented again — or a rotation race lost — is reuse, and reuse
        // retires the whole chain (stack-architecture.md decision 9).
        if (stored.rotatedAt() != null || !store.markRotated(hash, now)) {
            store.revokeChain(stored.chainId(), now);
            throw new OAuthServiceException(OAuthConstants.INVALID_GRANT);
        }
        UserSubject subject = new UserSubject(stored.loginId(), stored.subject());
        if (stored.actingRole() != null) {
            subject.getProperties().put(ACTING_ROLE, stored.actingRole());
        }
        return mint(client, subject, stored.resourceId(), stored.actingRole(),
                stored.chainId());
    }

    /**
     * Refusal, until it has a caller: nothing stored answers a token-by-value lookup, and the
     * method acquires one only with RFC 7662 introspection or RFC 7009 revocation — at which
     * point the answer is reconstruction from validated claims, not retrieval.
     */
    @Override
    public ServerAccessToken getAccessToken(String accessToken) {
        throw new UnsupportedOperationException(
                "access tokens are stateless (docs/token-issuance.md decision 2)");
    }

    /** Null is this method's ordinary answer and means "mint a new one" — see decision 4. */
    @Override
    public ServerAccessToken getPreauthorizedToken(Client client, List<String> requestedScopes,
            UserSubject subject, String grantType) {
        return null;
    }

    /** Refusal: nothing enumerates tokens this server does not keep. */
    @Override
    public List<ServerAccessToken> getAccessTokens(Client client, UserSubject subject) {
        throw new UnsupportedOperationException(
                "access tokens are stateless (docs/token-issuance.md decision 2)");
    }

    /**
     * The live rows behind a subject's "applications you have authorised" page. The returned
     * models carry the store hash as their key — the wire value is not kept — so revocation
     * goes through the store's chain operations, never back through a model's key.
     */
    @Override
    public List<RefreshToken> getRefreshTokens(Client client, UserSubject subject) {
        List<RefreshToken> tokens = new ArrayList<>();
        for (IssuedRefreshToken stored : store.refreshTokensFor(
                client == null ? null : client.getClientId(), subjectId(subject))) {
            RefreshToken token = new RefreshToken(client, stored.tokenHash(),
                    Duration.between(stored.issuedAt(), stored.expiresAt()).toSeconds(),
                    stored.issuedAt().getEpochSecond());
            token.setSubject(new UserSubject(stored.loginId(), stored.subject()));
            if (stored.resourceId() != null) {
                token.setAudiences(List.of(stored.resourceId()));
            }
            tokens.add(token);
        }
        return tokens;
    }

    /** The value is the refresh token itself; access tokens are stateless and expire instead. */
    @Override
    public void revokeToken(Client client, String tokenId, String tokenTypeHint) {
        Instant now = clock.instant();
        store.findRefreshToken(Tokens.sha256Hex(tokenId))
                .filter(stored -> client == null || stored.clientId().equals(client.getClientId()))
                .ifPresent(stored -> store.revokeChain(stored.chainId(), now));
    }

    /**
     * Nothing, deliberately (stack-architecture.md decision 11): the scope parameter is
     * accepted and grants nothing; authorisation stays by role and permission.
     */
    @Override
    public List<OAuthPermission> convertScopeToPermissions(Client client,
            List<String> requestedScopes) {
        return List.of();
    }

    private ServerAccessToken mint(Client client, UserSubject subject, String audience,
            String actingRole) {
        return mint(client, subject, audience, actingRole, UUID.randomUUID().toString());
    }

    private ServerAccessToken mint(Client client, UserSubject subject, String audience,
            String actingRole, String chainId) {
        Instant now = clock.instant();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subjectId(subject));
        if (login(subject) != null) {
            claims.put("login", login(subject));
        }
        if (audience != null) {
            claims.put("aud", audience);
        }
        if (actingRole != null) {
            claims.put(ACTING_ROLE, actingRole);
        }
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(accessTokenLifetime).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());

        BearerAccessToken token = new BearerAccessToken(client, signer.sign(claims),
                accessTokenLifetime.toSeconds(), now.getEpochSecond());
        token.setSubject(subject);
        if (audience != null) {
            token.setAudiences(List.of(audience));
        }

        String refreshToken = Tokens.newToken();
        store.saveRefreshToken(new IssuedRefreshToken(
                Tokens.sha256Hex(refreshToken),
                chainId,
                client.getClientId(),
                subjectId(subject),
                login(subject),
                audience,
                actingRole,
                now,
                now.plus(refreshTokenLifetime),
                null,
                null));
        token.setRefreshToken(refreshToken);
        return token;
    }

    private ServerAuthorizationCodeGrant reconstruct(IssuedCode stored) {
        Client client = getClient(stored.clientId());
        if (client == null) {
            return null;
        }
        Instant now = clock.instant();
        // issuedAt/expiresIn are reconstructed relative to now, so the handler's expiry check
        // sees a non-positive remaining lifetime exactly when the stored expiry has passed.
        ServerAuthorizationCodeGrant grant = new ServerAuthorizationCodeGrant(client,
                stored.codeHash(), stored.expiresAt().getEpochSecond() - now.getEpochSecond(),
                now.getEpochSecond());
        UserSubject subject = new UserSubject(stored.loginId(), stored.subject());
        if (stored.actingRole() != null) {
            subject.getProperties().put(ACTING_ROLE, stored.actingRole());
        }
        grant.setSubject(subject);
        grant.setRedirectUri(stored.redirectUri());
        if (stored.resourceId() != null) {
            grant.getExtraProperties().put(RESOURCE, stored.resourceId());
        }
        grant.setClientCodeChallenge(stored.codeChallenge());
        if (stored.codeChallenge() != null) {
            // S256 is the only accepted method (decision 4), so the method is a constant
            // rather than a column.
            grant.setClientCodeChallengeMethod("S256");
        }
        grant.setPreauthorizedTokenAvailable(false);
        return grant;
    }

    private static String subjectId(UserSubject subject) {
        return subject == null ? null : subject.getId();
    }

    private static String login(UserSubject subject) {
        return subject == null ? null : subject.getLogin();
    }

    private static String actingRole(UserSubject subject) {
        return subject == null ? null : subject.getProperties().get(ACTING_ROLE);
    }
}
