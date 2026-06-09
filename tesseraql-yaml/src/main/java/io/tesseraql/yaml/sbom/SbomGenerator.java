package io.tesseraql.yaml.sbom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.manifest.AppManifest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Generates a deterministic CycloneDX SBOM for an app package's source artifacts (design ch. 50).
 * Each YAML/SQL/template file is listed as a component with its SHA-256 hash; secret values are
 * never included.
 */
public final class SbomGenerator {

    private static final TqlErrorCode ERROR = new TqlErrorCode(TqlDomain.REPORT, 2201);
    private final ObjectMapper mapper = new ObjectMapper();

    /** Builds the CycloneDX document tree. */
    public Map<String, Object> cycloneDx(AppManifest manifest, String appName, String appVersion) {
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("version", 1);
        bom.put("metadata", Map.of("component",
                Map.of("type", "application", "name", appName, "version", appVersion)));

        List<Object> components = new ArrayList<>();
        new TreeMap<>(manifest.index().fileChecksums()).forEach((path, sha) -> {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("type", "file");
            component.put("name", path);
            component.put("hashes", List.of(Map.of("alg", "SHA-256", "content", sha)));
            components.add(component);
        });
        bom.put("components", components);
        return bom;
    }

    public String toJson(AppManifest manifest, String appName, String appVersion) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cycloneDx(manifest, appName, appVersion));
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Failed to serialize SBOM: " + ex.getMessage());
        }
    }
}
