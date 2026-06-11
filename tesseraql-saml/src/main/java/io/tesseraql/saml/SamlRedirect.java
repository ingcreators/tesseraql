package io.tesseraql.saml;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Encodes and decodes SAML messages for the HTTP-Redirect binding (design ch. 10.14, OASIS SAML
 * Bindings §3.4): the XML is raw-DEFLATE compressed and base64 encoded for the {@code SAMLRequest}
 * query parameter, and decoded the same way on receipt.
 */
public final class SamlRedirect {

    private SamlRedirect() {
    }

    /** Raw-DEFLATEs and base64-encodes a SAML message for a redirect {@code SAMLRequest}. */
    public static String deflateAndEncode(String xml) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(xml.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    /** The RSA-SHA256 signature algorithm identifier the redirect binding advertises. */
    public static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";

    /**
     * Builds the signed redirect query string (OASIS SAML Bindings 3.4.4.1): the signature is
     * computed over the exact URL-encoded octets of
     * {@code <param>=...[&RelayState=...]&SigAlg=...} and appended as {@code &Signature=...}.
     */
    public static String signedQuery(String paramName, String encodedMessage, String relayState,
            java.security.PrivateKey key) {
        String query = query(paramName, encodedMessage, relayState)
                + "&SigAlg=" + urlEncode(RSA_SHA256);
        try {
            java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
            signer.initSign(key);
            signer.update(query.getBytes(StandardCharsets.UTF_8));
            return query + "&Signature=" + urlEncode(
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (java.security.GeneralSecurityException ex) {
            throw new SamlException("Cannot sign redirect message: " + ex.getMessage(), ex);
        }
    }

    /** The unsigned redirect query string. */
    public static String query(String paramName, String encodedMessage, String relayState) {
        StringBuilder query = new StringBuilder(paramName).append('=')
                .append(urlEncode(encodedMessage));
        if (relayState != null && !relayState.isBlank()) {
            query.append("&RelayState=").append(urlEncode(relayState));
        }
        return query.toString();
    }

    /**
     * Verifies an inbound signed redirect message against the pinned IdP key, rebuilding the
     * exact signed octets from the received parameter values. Throws on any mismatch.
     */
    public static void verifySignedQuery(String paramName, String encodedMessage,
            String relayState, String sigAlg, String signatureBase64,
            java.security.PublicKey key) {
        if (sigAlg == null || signatureBase64 == null) {
            throw new SamlException("Redirect message is not signed");
        }
        if (!RSA_SHA256.equals(sigAlg)) {
            throw new SamlException("Unsupported redirect signature algorithm: " + sigAlg);
        }
        String query = query(paramName, encodedMessage, relayState)
                + "&SigAlg=" + urlEncode(sigAlg);
        try {
            java.security.Signature verifier = java.security.Signature.getInstance("SHA256withRSA");
            verifier.initVerify(key);
            verifier.update(query.getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(Base64.getMimeDecoder().decode(signatureBase64))) {
                throw new SamlException("Redirect signature does not verify");
            }
        } catch (java.security.GeneralSecurityException ex) {
            throw new SamlException("Cannot verify redirect signature: " + ex.getMessage(), ex);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Base64-decodes and raw-INFLATEs a redirect-binding SAML message. */
    public static String decodeAndInflate(String encoded) {
        byte[] compressed = Base64.getMimeDecoder().decode(encoded);
        Inflater inflater = new Inflater(true);
        inflater.setInput(compressed);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && inflater.needsInput()) {
                    break;
                }
                out.write(buffer, 0, n);
            }
        } catch (DataFormatException ex) {
            throw new SamlException("Malformed SAML redirect payload: " + ex.getMessage(), ex);
        } finally {
            inflater.end();
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
