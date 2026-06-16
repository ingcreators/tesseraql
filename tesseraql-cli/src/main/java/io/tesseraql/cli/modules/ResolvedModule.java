package io.tesseraql.cli.modules;

import java.nio.file.Path;

/**
 * One artifact in a resolved module closure: its fully-versioned {@code group:artifact:version}
 * coordinate, the resolved jar on disk, and its SHA-256 (recorded in {@code modules.lock} for
 * reproducible, supply-chain-checkable resolution).
 */
public record ResolvedModule(String coordinate, Path file, String sha256) {
}
