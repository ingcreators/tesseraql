package io.tesseraql.oauth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the published key set as an RFC 7517 JWK Set — the document every replica serves
 * identically because the keys live in the shared framework datasource (docs/token-issuance.md
 * decision 3). Only public halves leave this class.
 */
public final class JwksDocuments {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private JwksDocuments() {
    }

    public static String render(List<SigningKeys.SigningKey> keys) {
        List<Map<String, Object>> jwks = new ArrayList<>();
        for (SigningKeys.SigningKey key : keys) {
            RSAPublicKey publicKey = SigningKeys.publicKey(key);
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kty", "RSA");
            jwk.put("use", "sig");
            jwk.put("alg", "RS256");
            jwk.put("kid", key.kid());
            jwk.put("n", unsigned(publicKey.getModulus()));
            jwk.put("e", unsigned(publicKey.getPublicExponent()));
            jwks.add(jwk);
        }
        try {
            return MAPPER.writeValueAsString(Map.of("keys", jwks));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JWKS rendering failed", e);
        }
    }

    /** Base64url of the unsigned big-endian magnitude — RFC 7518's integer encoding. */
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length > 1 && bytes[0] == 0 ? 1 : 0;
        byte[] magnitude = new byte[bytes.length - start];
        System.arraycopy(bytes, start, magnitude, 0, magnitude.length);
        return URL.encodeToString(magnitude);
    }
}
