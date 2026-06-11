package io.tesseraql.yaml.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Verifies a release evidence document against the app it claims to describe (design ch. 49):
 * every recorded file hash must match the current source tree (both directions - changed, missing
 * and unrecorded files all fail), and when a detached signature envelope is given it must verify
 * over the exact evidence bytes, optionally pinned to an expected public key fingerprint. This is
 * the deploy-time counterpart of the {@code release-evidence} goal.
 */
public final class ReleaseEvidenceVerifier {

    private static final TqlErrorCode ERROR = new TqlErrorCode(TqlDomain.REPORT, 2103);
    private final ObjectMapper mapper = new ObjectMapper();

    /** One reason the evidence does not match. */
    public record Mismatch(String subject, String reason) {
    }

    /** The verification outcome; {@code verified} is true only with zero mismatches. */
    public record Result(boolean verified, List<Mismatch> mismatches) {
    }

    /**
     * Verifies the evidence JSON against the manifest, and the optional signature envelope
     * (nullable) against the evidence bytes and the optional expected key fingerprint (nullable).
     */
    public Result verify(AppManifest manifest, String evidenceJson, String signatureJson,
            String expectedKeySha256) {
        List<Mismatch> mismatches = new ArrayList<>();
        checkFiles(manifest, parse(evidenceJson), mismatches);
        if (signatureJson != null) {
            checkSignature(evidenceJson, signatureJson, expectedKeySha256, mismatches);
        } else if (expectedKeySha256 != null) {
            mismatches.add(new Mismatch("signature",
                    "an expected key fingerprint is configured but the evidence is unsigned"));
        }
        return new Result(mismatches.isEmpty(), List.copyOf(mismatches));
    }

    private void checkFiles(AppManifest manifest, Map<?, ?> evidence, List<Mismatch> mismatches) {
        Object recorded = evidence.get("files");
        if (!(recorded instanceof Map<?, ?> files)) {
            mismatches.add(new Mismatch("evidence", "no 'files' section"));
            return;
        }
        Map<String, String> current = new TreeMap<>(manifest.index().fileChecksums());
        for (Map.Entry<?, ?> entry : files.entrySet()) {
            String path = String.valueOf(entry.getKey());
            String actual = current.remove(path);
            if (actual == null) {
                mismatches.add(new Mismatch(path, "recorded in the evidence but missing"));
            } else if (!actual.equalsIgnoreCase(String.valueOf(entry.getValue()))) {
                mismatches.add(new Mismatch(path, "content changed since the evidence"));
            }
        }
        current.keySet().forEach(path -> mismatches
                .add(new Mismatch(path, "present but not recorded in the evidence")));
        Object aggregate = evidence.get("manifestSha256");
        if (aggregate != null && mismatches.isEmpty()
                && !manifest.index().aggregateHash().equalsIgnoreCase(String.valueOf(aggregate))) {
            mismatches.add(new Mismatch("manifestSha256", "aggregate hash mismatch"));
        }
    }

    private static void checkSignature(String evidenceJson, String signatureJson,
            String expectedKeySha256, List<Mismatch> mismatches) {
        EvidenceSignature signature = EvidenceSignature.parse(signatureJson);
        if (!signature.verifies(evidenceJson.getBytes(StandardCharsets.UTF_8))) {
            mismatches.add(new Mismatch("signature", "does not verify over the evidence bytes"));
        }
        if (expectedKeySha256 != null
                && !expectedKeySha256.equalsIgnoreCase(signature.publicKeySha256())) {
            mismatches.add(new Mismatch("signature", "signed by key " + signature.publicKeySha256()
                    + " but key " + expectedKeySha256 + " was expected"));
        }
    }

    private Map<?, ?> parse(String evidenceJson) {
        try {
            return mapper.readValue(evidenceJson, Map.class);
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Invalid evidence document: " + ex.getMessage());
        }
    }
}
