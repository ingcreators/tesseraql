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
 * Generates a deterministic CycloneDX SBOM for an app package (design ch. 50). Each YAML/SQL/
 * template file is listed as a file component with its SHA-256 hash, and the hosting project's
 * Maven dependencies as library components with their purl, jar hash and declared licenses.
 * Secret values are never included.
 */
public final class SbomGenerator {

    private static final TqlErrorCode ERROR = new TqlErrorCode(TqlDomain.REPORT, 2201);
    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();

    /** One resolved Maven dependency; {@code sha256} and {@code licenses} may be empty. */
    public record MavenComponent(String groupId, String artifactId, String version, String sha256,
            List<String> licenses) {

        String purl() {
            return "pkg:maven/" + groupId + "/" + artifactId + "@" + version;
        }
    }

    /** Builds the CycloneDX document tree without dependency components. */
    public Map<String, Object> cycloneDx(AppManifest manifest, String appName, String appVersion) {
        return cycloneDx(manifest, appName, appVersion, List.of());
    }

    /** Builds the CycloneDX document tree, dependencies ordered by purl for reproducibility. */
    public Map<String, Object> cycloneDx(AppManifest manifest, String appName, String appVersion,
            List<MavenComponent> dependencies) {
        Map<String, Object> bom = new LinkedHashMap<>();
        bom.put("bomFormat", "CycloneDX");
        bom.put("specVersion", "1.5");
        bom.put("version", 1);
        Map<String, Object> application = new LinkedHashMap<>();
        application.put("type", "application");
        application.put("name", appName);
        application.put("version", appVersion);
        bom.put("metadata", Map.of("component", application));

        List<Object> components = new ArrayList<>();
        new TreeMap<>(manifest.index().fileChecksums()).forEach((path, sha) -> {
            Map<String, Object> component = new LinkedHashMap<>();
            component.put("type", "file");
            component.put("name", path);
            component.put("hashes", List.of(hash(sha)));
            components.add(component);
        });
        dependencies.stream()
                .sorted(java.util.Comparator.comparing(MavenComponent::purl))
                .forEach(dependency -> components.add(library(dependency)));
        bom.put("components", components);
        return bom;
    }

    /**
     * One CycloneDX hash object. Built rather than declared with {@code Map.of}, whose iteration
     * order comes from a per-JVM salt and would put these two keys in a different order in every
     * build of identical source (docs/deterministic-output.md).
     */
    private static Map<String, Object> hash(String sha256) {
        Map<String, Object> hash = new LinkedHashMap<>();
        hash.put("alg", "SHA-256");
        hash.put("content", sha256);
        return hash;
    }

    private static Map<String, Object> library(MavenComponent dependency) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "library");
        component.put("group", dependency.groupId());
        component.put("name", dependency.artifactId());
        component.put("version", dependency.version());
        component.put("purl", dependency.purl());
        if (dependency.sha256() != null && !dependency.sha256().isBlank()) {
            component.put("hashes", List.of(hash(dependency.sha256())));
        }
        if (!dependency.licenses().isEmpty()) {
            component.put("licenses", dependency.licenses().stream()
                    .map(name -> Map.of("license", Map.of("name", name)))
                    .toList());
        }
        return component;
    }

    public String toJson(AppManifest manifest, String appName, String appVersion) {
        return toJson(manifest, appName, appVersion, List.of());
    }

    public String toJson(AppManifest manifest, String appName, String appVersion,
            List<MavenComponent> dependencies) {
        try {
            return mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(cycloneDx(manifest, appName, appVersion, dependencies));
        } catch (JsonProcessingException ex) {
            throw new TqlException(ERROR, "Failed to serialize SBOM: " + ex.getMessage());
        }
    }
}
