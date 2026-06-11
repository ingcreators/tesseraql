package io.tesseraql.saml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads the pinned IdP signing public key from its on-disk material (design ch. 10.14): an X.509
 * certificate (PEM or DER) or a bare public key (PEM {@code PUBLIC KEY} or DER SubjectPublicKeyInfo).
 * Extracting this key from IdP SAML metadata is handled separately.
 */
public final class SamlKeys {

    private SamlKeys() {
    }

    /**
     * Parses {@code material} into the SP's RSA signing private key: PKCS#8, PEM
     * ({@code PRIVATE KEY}) or DER. Used to sign HTTP-Redirect messages (design ch. 10.14).
     */
    public static java.security.PrivateKey privateKey(byte[] material) {
        try {
            String text = new String(material, java.nio.charset.StandardCharsets.US_ASCII);
            byte[] der = text.contains("-----")
                    ? java.util.Base64.getMimeDecoder().decode(
                            text.replaceAll("-----[A-Z ]+-----", ""))
                    : material;
            return java.security.KeyFactory.getInstance("RSA")
                    .generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(der));
        } catch (Exception ex) {
            throw new SamlException("Cannot load SAML SP private key: " + ex.getMessage(), ex);
        }
    }

    /** Parses {@code material} into a public key, trying the form indicated by any PEM header. */
    public static PublicKey publicKey(byte[] material) {
        String text = new String(material, StandardCharsets.UTF_8);
        try {
            if (text.contains("BEGIN CERTIFICATE")) {
                return certificateKey(pemBody(text, "CERTIFICATE"));
            }
            if (text.contains("BEGIN PUBLIC KEY")) {
                return rsaKey(pemBody(text, "PUBLIC KEY"));
            }
            try {
                return certificateKey(material);
            } catch (Exception notCertificate) {
                return rsaKey(material);
            }
        } catch (SamlException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SamlException("Cannot load SAML IdP public key: " + ex.getMessage(), ex);
        }
    }

    private static byte[] pemBody(String pem, String type) {
        String base64 = pem.replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }

    private static PublicKey certificateKey(byte[] der) throws Exception {
        return CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der)).getPublicKey();
    }

    private static PublicKey rsaKey(byte[] der) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }
}
