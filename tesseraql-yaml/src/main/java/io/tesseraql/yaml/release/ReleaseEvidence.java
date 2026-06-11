package io.tesseraql.yaml.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.util.Hashing;
import io.tesseraql.yaml.manifest.AppManifest;
import io.tesseraql.yaml.openapi.OpenApiGenerator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds release evidence tying an app version to the hashes of its source and generated artifacts
 * (design ch. 49). The document is deterministic (no timestamps or random ids) so it is reproducible
 * and signable.
 */
public final class ReleaseEvidence {

    private static final TqlErrorCode ERROR = new TqlErrorCode(TqlDomain.REPORT, 2101);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Builds the evidence document tree. */
    public Map<String, Object> build(AppManifest manifest, String appId, String appVersion) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("evidenceVersion", "tesseraql/evidence/v1");
        evidence.put("app", Map.of("id", appId, "version", appVersion));
        evidence.put("manifestSha256", manifest.index().aggregateHash());
        evidence.put("generated", Map.of(
                "openapiSha256", Hashing.sha256(new OpenApiGenerator().toJson(manifest))));
        evidence.put("files", new TreeMap<>(manifest.index().fileChecksums()));
        return evidence;
    }

    public String toJson(AppManifest manifest, String appId, String appVersion) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(build(manifest, appId, appVersion));
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Failed to serialize release evidence: " + ex.getMessage());
        }
    }
}
