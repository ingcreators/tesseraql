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
