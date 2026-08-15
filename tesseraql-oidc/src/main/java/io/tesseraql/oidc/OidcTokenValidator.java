package io.tesseraql.oidc;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwksConfig;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.jwt.JwtAuthenticator;
import java.util.List;

/**
 * Validates an OpenID Connect ID token (roadmap Phase 25). Signature, {@code exp}/{@code nbf},
 * {@code iss} and {@code aud} are delegated to {@link JwtAuthenticator} (RS256 against the
 * provider's JWKS); only the {@code nonce} check remains here. The expected issuer is always the
 * discovered {@code issuer}, so the iss check is never silently skipped.
 *
 * <p>The audience check used to live here, with a javadoc explaining that it did "so the bearer path
 * stays untouched" — treating {@code aud} as OIDC-specific. It is not: it is RFC 7519 §4.1.3, and
 * leaving the bearer path without it was a confused deputy (docs/audit-hardening.md Decision 1).
 * There is now one implementation, in {@link JwtAuthenticator}, and this validator uses it by
 * declaring the client id as the audience it expects.
 */
public final class OidcTokenValidator {

    private final JwtAuthenticator authenticator;

    public OidcTokenValidator(OidcMetadata metadata, OidcConfig config) {
        this(buildAuthenticator(metadata, config));
    }

    /**
     * Test seam: inject a {@link JwtAuthenticator} (e.g. a static-key one) without a JWKS fetch.
     *
     * <p>It no longer takes a client id beside the authenticator. The audience is now the
     * authenticator's, so passing one here would let a test declare an expectation the validator
     * does not consult.
     */
    OidcTokenValidator(JwtAuthenticator authenticator) {
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
                // An ID token's aud is the client id — the same intersection rule the bearer path
                // now runs, rather than a second copy of it here.
                List.of(config.clientId()),
                config.clockSkew(),
                // An ID token without exp is refused, which is what the shared default already says.
                null,
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
        requireNonce(principal.claims().get("nonce"), expectedNonce);
        return principal;
    }

    private static void requireNonce(Object nonce, String expected) {
        if (expected != null && !expected.equals(nonce)) {
            throw new OidcException("ID token nonce mismatch");
        }
    }
}
