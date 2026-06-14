package io.tesseraql.security.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlException;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses RSA public keys for RS256 verification (design ch. 11.1), JDK-only — no JOSE library. A
 * key arrives either as a JWK / JWK Set (JSON, as a JWKS endpoint serves) or as PEM/DER (a
 * {@code PUBLIC KEY} SubjectPublicKeyInfo or an X.509 {@code CERTIFICATE}), mirroring the SAML
 * module's key handling so the supply-chain posture is the same.
 */
public final class Jwks {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Decoder URL_DECODER = Base64.getUrlDecoder();

    private Jwks() {
    }

    /**
     * Parses a single configured {@code publicKey}: a JWK / JWK Set (first RSA key), a PEM
     * {@code PUBLIC KEY} or {@code CERTIFICATE}, or raw DER.
     */
    public static RSAPublicKey parsePublicKey(String material) {
        if (material == null || material.isBlank()) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "Empty JWT public key");
        }
        String trimmed = material.trim();
        try {
            if (trimmed.startsWith("{")) {
                JsonNode node = MAPPER.readTree(trimmed);
                JsonNode jwk = node.has("keys") ? firstRsaKey(node.get("keys")) : node;
                return fromJwk(jwk);
            }
            if (trimmed.contains("BEGIN CERTIFICATE")) {
                return certificateKey(pemBody(trimmed));
            }
            return spkiKey(trimmed.contains("BEGIN")
                    ? pemBody(trimmed)
                    : material.getBytes(StandardCharsets.US_ASCII));
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "Cannot load JWT public key: " + ex.getMessage());
        }
    }

    /** Parses a JWK Set JSON document into a map of {@code kid} to RSA key (a kid-less key maps to "").*/
    public static Map<String, RSAPublicKey> parseJwkSet(byte[] json) {
        try {
            JsonNode keys = MAPPER.readTree(json).get("keys");
            if (keys == null || !keys.isArray()) {
                throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWKS has no 'keys' array");
            }
            Map<String, RSAPublicKey> result = new LinkedHashMap<>();
            for (JsonNode jwk : keys) {
                if (!"RSA".equals(text(jwk, "kty")) || isEncryptionKey(jwk)) {
                    continue;
                }
                String kid = text(jwk, "kid");
                result.put(kid == null ? "" : kid, fromJwk(jwk));
            }
            if (result.isEmpty()) {
                throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                        "JWKS has no RSA signing keys");
            }
            return result;
        } catch (TqlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "Cannot parse JWKS: " + ex.getMessage());
        }
    }

    /** Builds an RSA public key from a single JWK node ({@code kty=RSA}, base64url {@code n}/{@code e}). */
    public static RSAPublicKey fromJwk(JsonNode jwk) {
        if (jwk == null || !"RSA".equals(text(jwk, "kty"))) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "Not an RSA JWK");
        }
        if (isEncryptionKey(jwk)) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWK is not a signing key");
        }
        String n = text(jwk, "n");
        String e = text(jwk, "e");
        if (n == null || e == null) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED, "RSA JWK missing 'n' or 'e'");
        }
        try {
            BigInteger modulus = new BigInteger(1, URL_DECODER.decode(n));
            BigInteger exponent = new BigInteger(1, URL_DECODER.decode(e));
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        } catch (Exception ex) {
            throw new TqlException(SignatureVerifier.UNAUTHORIZED,
                    "Cannot build RSA key from JWK: " + ex.getMessage());
        }
    }

    private static JsonNode firstRsaKey(JsonNode keys) {
        for (JsonNode jwk : keys) {
            if ("RSA".equals(text(jwk, "kty")) && !isEncryptionKey(jwk)) {
                return jwk;
            }
        }
        throw new TqlException(SignatureVerifier.UNAUTHORIZED, "JWK Set has no RSA signing key");
    }

    private static boolean isEncryptionKey(JsonNode jwk) {
        return "enc".equals(text(jwk, "use"));
    }

    private static RSAPublicKey spkiKey(byte[] der) throws Exception {
        return (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(der));
    }

    private static RSAPublicKey certificateKey(byte[] der) throws Exception {
        return (RSAPublicKey) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
    }

    private static byte[] pemBody(String pem) {
        String base64 = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
