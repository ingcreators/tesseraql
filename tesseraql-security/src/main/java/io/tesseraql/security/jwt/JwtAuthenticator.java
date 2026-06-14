package io.tesseraql.security.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig.JwtConfig;
import io.tesseraql.security.policy.PolicyEngine;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Verifies and parses bearer JWTs into a {@link Principal} (design ch. 11.1 {@code bearer}).
 *
 * <p>The signature check is delegated to a {@link SignatureVerifier} chosen from the configured
 * algorithm ({@code HS256} or {@code RS256}). The authenticator binds the expected algorithm from
 * configuration and rejects any token whose header {@code alg} differs (and {@code none}) before a
 * key is touched, closing the classic algorithm-confusion hole (an RS256 public key used as an
 * HMAC secret). OIDC, SAML, API key, and mTLS plug in behind the same authentication step.
 */
public final class JwtAuthenticator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private final JwtConfig config;
    private final SignatureVerifier verifier;

    /** Builds the verifier from {@code config}; the production path used by the runtime and CLI. */
    public JwtAuthenticator(JwtConfig config) {
        this(config, verifierFor(config));
    }

    /** Uses a supplied verifier, so tests can inject a fake JWKS key source without a network. */
    public JwtAuthenticator(JwtConfig config, SignatureVerifier verifier) {
        this.config = config;
        this.verifier = verifier;
    }

    private static SignatureVerifier verifierFor(JwtConfig config) {
        return switch (config.algorithm()) {
            case "HS256" -> new HmacSignatureVerifier(config.secret());
            case "RS256" -> new RsaSignatureVerifier(rsaKeySource(config));
            default -> throw new TqlException(PolicyEngine.UNAUTHORIZED,
                    "Unsupported JWT algorithm: " + config.algorithm());
        };
    }

    private static KeySource rsaKeySource(JwtConfig config) {
        if (config.publicKey() != null && !config.publicKey().isBlank()) {
            return new StaticKeySource(Jwks.parsePublicKey(config.publicKey()));
        }
        throw new TqlException(PolicyEngine.UNAUTHORIZED,
                "RS256 JWT config must declare a key source (jwksUri or publicKey)");
    }

    /** Authenticates the value of an HTTP {@code Authorization} header. */
    public Principal authenticate(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Missing bearer token");
        }
        return parse(authorizationHeader.substring("Bearer ".length()).trim());
    }

    @SuppressWarnings("unchecked")
    private Principal parse(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Malformed JWT");
        }
        Map<String, Object> header;
        try {
            header = MAPPER.readValue(URL_DECODER.decode(parts[0]), Map.class);
        } catch (Exception ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid JWT header");
        }
        // Bind the expected algorithm from configuration, never from the token: a token asking for
        // a different alg (or "none") is rejected before any key material is consulted.
        String alg = asString(header.get("alg"));
        if (!config.algorithm().equals(alg)) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Unexpected JWT alg: " + alg);
        }
        verifier.verify(parts[0] + "." + parts[1], URL_DECODER.decode(parts[2]),
                asString(header.get("kid")));

        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(URL_DECODER.decode(parts[1]), Map.class);
        } catch (Exception ex) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "Invalid JWT payload");
        }
        validateClaims(claims);
        return toPrincipal(claims);
    }

    private void validateClaims(Map<String, Object> claims) {
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long skew = config.clockSkew().toSeconds();
        if (claims.get("exp") instanceof Number expSeconds
                && nowSeconds - skew >= expSeconds.longValue()) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT has expired");
        }
        // RS256 IdP tokens commonly carry nbf; honor it within the configured leeway.
        if (claims.get("nbf") instanceof Number nbfSeconds
                && nbfSeconds.longValue() > nowSeconds + skew) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT is not yet valid");
        }
        if (config.issuer() != null && !config.issuer().equals(claims.get("iss"))) {
            throw new TqlException(PolicyEngine.UNAUTHORIZED, "JWT issuer mismatch");
        }
    }

    private Principal toPrincipal(Map<String, Object> claims) {
        return new Principal(
                asString(claims.get("sub")),
                asString(claims.get(config.loginClaim())),
                asString(claims.get(config.nameClaim())),
                asString(claims.get(config.tenantClaim())),
                asStringList(claims.get(config.groupsClaim())),
                asStringList(claims.get(config.rolesClaim())),
                asStringList(claims.get(config.permissionsClaim())),
                claims);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            list.forEach(element -> result.add(String.valueOf(element)));
            return result;
        }
        if (value instanceof String string && !string.isBlank()) {
            return List.of(string.split("\\s+"));
        }
        return List.of();
    }
}
