package io.tesseraql.yaml.manifest;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Checksum index of the source files that make up an app manifest (design ch. 22.20, 48).
 *
 * <p>The per-file SHA-256 digests and the {@link #aggregateHash()} provide the foundation for
 * reproducibility tracking and release evidence. File entries are keyed by app-home-relative path
 * using {@code /} separators so the index is platform independent.
 */
public record ManifestIndex(SortedMap<String, String> fileChecksums, String aggregateHash) {

    public ManifestIndex {
        fileChecksums = new TreeMap<>(fileChecksums);
    }

    @Override
    public SortedMap<String, String> fileChecksums() {
        return java.util.Collections.unmodifiableSortedMap(fileChecksums);
    }

    public static ManifestIndex of(Map<String, String> checksums, String aggregateHash) {
        return new ManifestIndex(new TreeMap<>(checksums), aggregateHash);
    }
}
