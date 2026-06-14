package io.tesseraql.oidc;

import java.net.URI;

/**
 * The OpenID Provider metadata an RP needs, from the discovery document (OpenID Connect Discovery
 * 1.0 §3). {@link #issuer()} is the value the ID token's {@code iss} claim must equal.
 *
 * @param issuer                the OP issuer identifier
 * @param authorizationEndpoint where the user-agent is redirected to authenticate
 * @param tokenEndpoint         where the authorization code is exchanged for tokens
 * @param jwksUri               the JWKS endpoint whose keys verify the ID token signature
 * @param endSessionEndpoint    the RP-initiated logout endpoint, or null when the OP omits it
 */
public record OidcMetadata(
        String issuer,
        URI authorizationEndpoint,
        URI tokenEndpoint,
        URI jwksUri,
        URI endSessionEndpoint) {
}
