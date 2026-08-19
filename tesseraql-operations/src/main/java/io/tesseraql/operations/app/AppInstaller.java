package io.tesseraql.operations.app;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.yaml.SimpleYamlParser;
import io.tesseraql.yaml.config.AppConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs a {@code .tqlapp} package into an install root and records it in the {@link AppCatalog}
 * (design ch. 32.4). Extraction is path-confined (zip-slip safe, ch. 20.2); the app name and version
 * come from the packaged config, and an optional config overlay is written so per-install settings
 * take precedence at load time (ch. 32.6).
 */
public final class AppInstaller {

    private static final TqlErrorCode INVALID_PACKAGE = new TqlErrorCode(TqlDomain.APP, 4041);
    private static final TqlErrorCode INSTALL_ERROR = new TqlErrorCode(TqlDomain.APP, 5002);
    /** TQL-APP-4090: this version is already installed with different content. */
    private static final TqlErrorCode VERSION_IMMUTABLE = new TqlErrorCode(TqlDomain.APP, 4090);

    private final SimpleYamlParser parser = new SimpleYamlParser();

    /** Installs {@code tqlapp} into {@code installRoot}, returning the catalog entry. */
    public InstalledApp install(Path tqlapp, Path installRoot) {
        return install(tqlapp, installRoot, null, List.of());
    }

    /**
     * Installs {@code tqlapp} after verifying its SHA-256 against {@code expectedSha256}
     * (design ch. 49, 50): a tampered or corrupted package is rejected before extraction.
     */
    public InstalledApp install(Path tqlapp, Path installRoot, String expectedSha256) {
        verifyIntegrity(tqlapp, expectedSha256);
        return install(tqlapp, installRoot, null, List.of());
    }

    /** Rejects the package when its SHA-256 does not match the expected value. */
    public static void verifyIntegrity(Path tqlapp, String expectedSha256) {
        String actual = io.tesseraql.core.util.Hashing.sha256(tqlapp);
        if (!actual.equalsIgnoreCase(expectedSha256 == null ? "" : expectedSha256.trim())) {
            throw new TqlException(INVALID_PACKAGE, "Package integrity check failed for "
                    + tqlapp.getFileName() + ": expected sha256 " + expectedSha256
                    + " but was " + actual);
        }
    }

    /**
     * Installs {@code tqlapp} into {@code installRoot}, applying {@code overlay} (may be null) and
     * recording {@code entitledTenants} (empty = all tenants).
     */
    public InstalledApp install(Path tqlapp, Path installRoot, Path overlay,
            List<String> entitledTenants) {
        InstalledApp app = place(tqlapp, installRoot, overlay, entitledTenants);
        new AppCatalog(installRoot).register(app);
        return app;
    }

    /**
     * Extracts and places {@code tqlapp} into the install root without touching the catalog, returning
     * the resulting entry. Used by upgrades to stage a candidate version (canary) before promotion.
     */
    public InstalledApp place(Path tqlapp, Path installRoot, Path overlay,
            List<String> entitledTenants) {
        try {
            Files.createDirectories(installRoot);
            Path staging = Files.createTempDirectory(installRoot, "staging-");
            try {
                extract(tqlapp, staging);
                applyOverlay(staging, overlay);

                AppConfig config = loadConfig(staging);
                String name = config.getString("tesseraql.app.name")
                        .orElseThrow(() -> new TqlException(
                                INVALID_PACKAGE, "Package has no tesseraql.app.name: " + tqlapp));
                String version = config.getString("tesseraql.app.version").orElse("0.0.0");

                // A placed version directory is immutable (docs/runtime-footprint.md decision 4):
                // nothing here ever deletes or replaces a tree a running host may hold files
                // open under — Windows refuses that mid-walk and leaves a half-deleted version,
                // Linux silently tolerates it, and either way rollback's "the previous version's
                // files must still be present" depends on placed trees staying whole. Re-placing
                // byte-identical content is the idempotent re-install
                // (docs/stack-architecture.md Decision 23) as a true no-op; different content
                // under an existing version is refused — moving a name forward means a new
                // version. With the target guaranteed absent, the move is a same-volume rename:
                // a crash mid-install leaves a stray staging directory, never a partial target.
                Path target = installRoot.resolve(name).resolve(version);
                if (Files.isDirectory(target)) {
                    if (!fileHashes(staging).equals(fileHashes(target))) {
                        throw new TqlException(VERSION_IMMUTABLE, "Version " + version
                                + " of app '" + name + "' is already installed with different"
                                + " content; a placed version directory is immutable — bump the"
                                + " package version.");
                    }
                } else {
                    Files.createDirectories(target.getParent());
                    Files.move(staging, target);
                }

                return new InstalledApp(name, version,
                        installRoot.relativize(target).toString().replace('\\', '/'),
                        entitledTenants);
            } finally {
                deleteRecursively(staging);
            }
        } catch (IOException ex) {
            throw new TqlException(INSTALL_ERROR, "Failed to install package: " + ex.getMessage());
        }
    }

    /** Reads a package's name, version, and required framework range without installing it. */
    public PackageInfo peek(Path tqlapp) {
        try {
            Path staging = Files.createTempDirectory("peek-");
            try {
                extract(tqlapp, staging);
                AppConfig config = loadConfig(staging);
                String name = config.getString("tesseraql.app.name")
                        .orElseThrow(() -> new TqlException(
                                INVALID_PACKAGE, "Package has no tesseraql.app.name: " + tqlapp));
                String version = config.getString("tesseraql.app.version").orElse("0.0.0");
                String requiresFramework = config.getString("tesseraql.app.requires.framework")
                        .orElse("*");
                return new PackageInfo(name, version, requiresFramework);
            } finally {
                deleteRecursively(staging);
            }
        } catch (IOException ex) {
            throw new TqlException(INVALID_PACKAGE, "Failed to read package: " + ex.getMessage());
        }
    }

    /** Package metadata read from a {@code .tqlapp} without installing it. */
    public record PackageInfo(String name, String version, String requiresFramework) {
    }

    private void extract(Path tqlapp, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tqlapp))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = root.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(root)) {
                    throw new TqlException(INVALID_PACKAGE,
                            "Package entry escapes install root (design ch. 20.2): "
                                    + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(resolved);
                } else {
                    Files.createDirectories(resolved.getParent());
                    Files.copy(zip, resolved, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void applyOverlay(Path appDir, Path overlay) throws IOException {
        if (overlay == null) {
            return;
        }
        Path configDir = appDir.resolve("config");
        Files.createDirectories(configDir);
        Files.copy(overlay, configDir.resolve("overlay.yml"), StandardCopyOption.REPLACE_EXISTING);
    }

    private AppConfig loadConfig(Path appDir) {
        Map<String, Object> merged = new HashMap<>();
        merged.putAll(parseIfPresent(appDir.resolve("config/application.yml")));
        merged.putAll(parseIfPresent(appDir.resolve("config/tesseraql.yml")));
        merged.putAll(parseIfPresent(appDir.resolve("config/overlay.yml")));
        return new AppConfig(merged);
    }

    private Map<String, Object> parseIfPresent(Path file) {
        return Files.isRegularFile(file) ? parser.parseTree(file) : Map.of();
    }

    /** The tree's regular files as slash-separated relative path → SHA-256, for equality checks. */
    private static Map<String, String> fileHashes(Path root) throws IOException {
        Map<String, String> hashes = new java.util.TreeMap<>();
        try (var files = Files.walk(root)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (Files.isRegularFile(path)) {
                    hashes.put(root.relativize(path).toString().replace('\\', '/'),
                            io.tesseraql.core.util.Hashing.sha256(path));
                }
            }
        }
        return hashes;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
