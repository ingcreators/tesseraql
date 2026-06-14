package io.tesseraql.oidc;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwksConfig;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import java.util.List;

/**
 * Validates an OpenID Connect ID token (roadmap Phase 25). Signature, {@code exp}/{@code nbf}, and
 * {@code iss} are delegated to {@link JwtAuthenticator} (RS256 against the provider's JWKS — the
 * slice-1 verifier); the OIDC-specific {@code aud} and {@code nonce} checks live here so the bearer
 * path stays untouched. The expected issuer is always the discovered {@code issuer}, so the iss
 * check is never silently skipped.
 */
public final class OidcTokenValidator {

    private final String clientId;
    private final JwtAuthenticator authenticator;

    public OidcTokenValidator(OidcMetadata metadata, OidcConfig config) {
        this(config.clientId(), buildAuthenticator(metadata, config));
    }

    /** Test seam: inject a {@link JwtAuthenticator} (e.g. a static-key one) without a JWKS fetch. */
    OidcTokenValidator(String clientId, JwtAuthenticator authenticator) {
        this.clientId = clientId;
        this.authenticator = authenticator;
    }

    private static JwtAuthenticator buildAuthenticator(OidcMetadata metadata, OidcConfig config) {
        OidcConfig.Claims claims = config.claims();
        JwtConfig jwt = new JwtConfig(
                "RS256",
                null,
                null,
                metadata.jwksUri().toString(),
                new JwksConfig(null, null, null),
                metadata.issuer(),
                config.clockSkew(),
                claims.roles(),
                null,
                claims.groups(),
                claims.tenant(),
                claims.login(),
                claims.name());
        return new JwtAuthenticator(jwt);
    }

    /**
     * Validates the ID token and returns the principal its claims map to.
     *
     * @param idToken       the raw ID token (a compact JWT)
     * @param expectedNonce the nonce recorded against the authorization request's state
     * @throws OidcException on any failure (signature, exp/nbf, iss, aud, or nonce)
     */
    public Principal validate(String idToken, String expectedNonce) {
        if (idToken == null || idToken.isBlank()) {
            throw new OidcException("Missing ID token");
        }
        Principal principal;
        try {
            principal = authenticator.authenticate("Bearer " + idToken);
        } catch (TqlException ex) {
            throw new OidcException("ID token rejected: " + ex.getMessage());
        }
        requireAudience(principal.claims().get("aud"));
        requireNonce(principal.claims().get("nonce"), expectedNonce);
        return principal;
    }

    private void requireAudience(Object aud) {
        boolean matches = aud instanceof String single && single.equals(clientId)
                || aud instanceof List<?> many && many.contains(clientId);
        if (!matches) {
            throw new OidcException("ID token audience does not include the client id");
        }
    }

    private static void requireNonce(Object nonce, String expected) {
        if (expected != null && !expected.equals(nonce)) {
            throw new OidcException("ID token nonce mismatch");
        }
    }
}
