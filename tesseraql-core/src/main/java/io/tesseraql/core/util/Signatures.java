package io.tesseraql.core.util;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Ed25519 detached signatures used for release evidence and plugin verification (design ch. 47,
 * 49). Keys are exchanged as base64 of their standard DER encodings (PKCS#8 private, X.509
 * SubjectPublicKeyInfo public), with or without PEM armor - the format
 * {@code openssl genpkey -algorithm ed25519} produces.
 */
public final class Signatures {

    private static final String ALGORITHM = "Ed25519";

    private Signatures() {
    }

    /** A freshly generated key pair, base64-encoded (private PKCS#8, public X.509). */
    public record GeneratedKeyPair(String privateKey, String publicKey) {
    }

    public static GeneratedKeyPair generateKeyPair() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
            Base64.Encoder base64 = Base64.getEncoder();
            return new GeneratedKeyPair(
                    base64.encodeToString(pair.getPrivate().getEncoded()),
                    base64.encodeToString(pair.getPublic().getEncoded()));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(ALGORITHM + " not available", ex);
        }
    }

    /** Signs the payload with a base64/PEM PKCS#8 private key; returns the base64 signature. */
    public static String sign(byte[] payload, String privateKey) {
        try {
            PrivateKey key = KeyFactory.getInstance(ALGORITHM)
                    .generatePrivate(new PKCS8EncodedKeySpec(decodeKey(privateKey)));
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(key);
            signer.update(payload);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("Invalid " + ALGORITHM + " private key", ex);
        }
    }

    /** Verifies a base64 signature over the payload with a base64/PEM X.509 public key. */
    public static boolean verify(byte[] payload, String signatureBase64, String publicKey) {
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(parsePublicKey(publicKey));
            verifier.update(payload);
            return verifier.verify(Base64.getDecoder().decode(signatureBase64.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            return false;
        }
    }

    /** The SHA-256 hex fingerprint of a public key's DER encoding - safe to log and pin. */
    public static String fingerprint(String publicKey) {
        return Hashing.sha256(decodeKey(publicKey));
    }

    private static PublicKey parsePublicKey(String publicKey) throws GeneralSecurityException {
        return KeyFactory.getInstance(ALGORITHM)
                .generatePublic(new X509EncodedKeySpec(decodeKey(publicKey)));
    }

    /** Accepts raw base64 or PEM armor and returns the DER bytes. */
    private static byte[] decodeKey(String key) {
        String body = key.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new IllegalArgumentException("Empty key");
        }
        return Base64.getDecoder().decode(body.getBytes(StandardCharsets.US_ASCII));
    }
}
