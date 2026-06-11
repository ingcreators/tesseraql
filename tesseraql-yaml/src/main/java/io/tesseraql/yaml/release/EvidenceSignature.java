package io.tesseraql.yaml.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Signatures;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A detached Ed25519 signature over a release evidence document (design ch. 49). The envelope
 * carries the public key and its fingerprint so verification is self-contained; trust comes from
 * checking the fingerprint against an out-of-band pinned value, never from the envelope itself.
 * The envelope is deterministic (no timestamps) like the evidence it signs.
 */
public record EvidenceSignature(String algorithm, String publicKey, String publicKeySha256,
        String signature) {

    private static final TqlErrorCode ERROR = new TqlErrorCode(TqlDomain.REPORT, 2102);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Signs the evidence payload with a base64/PEM PKCS#8 Ed25519 private key. */
    public static EvidenceSignature sign(byte[] payload, String privateKey, String publicKey) {
        return new EvidenceSignature("Ed25519", stripArmor(publicKey),
                Signatures.fingerprint(publicKey), Signatures.sign(payload, privateKey));
    }

    /** Whether this envelope's signature is valid for the payload. */
    public boolean verifies(byte[] payload) {
        return Signatures.verify(payload, signature, publicKey);
    }

    public String toJson() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("algorithm", algorithm);
        doc.put("publicKey", publicKey);
        doc.put("publicKeySha256", publicKeySha256);
        doc.put("signature", signature);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Failed to serialize evidence signature: "
                    + ex.getMessage());
        }
    }

    public static EvidenceSignature parse(String json) {
        try {
            Map<?, ?> doc = MAPPER.readValue(json, Map.class);
            return new EvidenceSignature(text(doc, "algorithm"), text(doc, "publicKey"),
                    text(doc, "publicKeySha256"), text(doc, "signature"));
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Invalid evidence signature document: "
                    + ex.getMessage());
        }
    }

    private static String text(Map<?, ?> doc, String key) {
        Object value = doc.get(key);
        if (value == null) {
            throw new TqlException(ERROR, "Evidence signature is missing '" + key + "'");
        }
        return String.valueOf(value);
    }

    private static String stripArmor(String key) {
        return key.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    }
}
