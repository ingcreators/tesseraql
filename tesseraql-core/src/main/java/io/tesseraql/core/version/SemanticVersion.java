package io.tesseraql.core.version;

import java.util.Objects;

/**
 * A simplified semantic version {@code major.minor.patch} (design ch. 30.1).
 *
 * <p>Missing components default to zero ({@code 1} == {@code 1.0.0}). Pre-release and build metadata
 * are ignored for ordering; comparison is numeric by major, then minor, then patch.
 */
public record SemanticVersion(int major, int minor,
        int patch) implements Comparable<SemanticVersion> {

    public SemanticVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
    }

    /** Parses {@code x}, {@code x.y}, or {@code x.y.z} (optionally with a {@code -pre}/{@code +build} suffix). */
    public static SemanticVersion parse(String value) {
        Objects.requireNonNull(value, "value");
        String core = value.trim();
        int cut = indexOfAny(core, '-', '+');
        if (cut >= 0) {
            core = core.substring(0, cut);
        }
        String[] parts = core.split("\\.");
        if (parts.length == 0 || parts.length > 3 || core.isBlank()) {
            throw new IllegalArgumentException("Not a version: " + value);
        }
        try {
            int major = Integer.parseInt(parts[0].trim());
            int minor = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : 0;
            return new SemanticVersion(major, minor, patch);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Not a version: " + value, ex);
        }
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int byMajor = Integer.compare(major, other.major);
        if (byMajor != 0) {
            return byMajor;
        }
        int byMinor = Integer.compare(minor, other.minor);
        return byMinor != 0 ? byMinor : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static int indexOfAny(String value, char a, char b) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == a || c == b) {
                return i;
            }
        }
        return -1;
    }
}
