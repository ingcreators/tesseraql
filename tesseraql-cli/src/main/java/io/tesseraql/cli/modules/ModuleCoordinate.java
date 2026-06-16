package io.tesseraql.cli.modules;

/**
 * A declared opt-in module coordinate, {@code groupId:artifactId} (version supplied by the
 * TesseraQL BOM) or {@code groupId:artifactId:version} (pinned explicitly). These are the entries
 * of {@code tesseraql.modules} in {@code config/tesseraql.yml} (design: app-developer-distribution
 * work item 4).
 */
public record ModuleCoordinate(String groupId, String artifactId, String version) {

    /** Parses {@code group:artifact} or {@code group:artifact:version}; rejects anything else. */
    public static ModuleCoordinate parse(String coordinate) {
        if (coordinate == null) {
            throw new IllegalArgumentException("Null module coordinate");
        }
        String[] parts = coordinate.trim().split(":");
        if (parts.length < 2 || parts.length > 3) {
            throw new IllegalArgumentException("Module coordinate must be group:artifact or"
                    + " group:artifact:version: '" + coordinate + "'");
        }
        for (String part : parts) {
            if (part.isBlank()) {
                throw new IllegalArgumentException("Blank segment in module coordinate: '"
                        + coordinate + "'");
            }
        }
        return new ModuleCoordinate(parts[0], parts[1], parts.length == 3 ? parts[2] : null);
    }

    /** {@code true} when the version is pinned in the coordinate rather than left to the BOM. */
    public boolean hasVersion() {
        return version != null && !version.isBlank();
    }

    /** The {@code group:artifact} key, independent of version. */
    public String ga() {
        return groupId + ":" + artifactId;
    }

    /** The canonical {@code group:artifact[:version]} string. */
    public String canonical() {
        return hasVersion() ? ga() + ":" + version : ga();
    }

    @Override
    public String toString() {
        return canonical();
    }
}
