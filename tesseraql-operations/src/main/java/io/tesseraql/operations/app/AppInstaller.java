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
 * (design ch. 32.4). Extraction is path-confined (zip-slip safe, ch. 20.2); the app id and version
 * come from the packaged config, and an optional config overlay is written so per-install settings
 * take precedence at load time (ch. 32.6).
 */
public final class AppInstaller {

    private static final TqlErrorCode INVALID_PACKAGE = new TqlErrorCode(TqlDomain.APP, 4041);
    private static final TqlErrorCode INSTALL_ERROR = new TqlErrorCode(TqlDomain.APP, 5002);

    private final SimpleYamlParser parser = new SimpleYamlParser();

    /** Installs {@code tqlapp} into {@code installRoot}, returning the catalog entry. */
    public InstalledApp install(Path tqlapp, Path installRoot) {
        return install(tqlapp, installRoot, null, List.of());
    }

    /**
     * Installs {@code tqlapp} into {@code installRoot}, applying {@code overlay} (may be null) and
     * recording {@code entitledTenants} (empty = all tenants).
     */
    public InstalledApp install(Path tqlapp, Path installRoot, Path overlay,
            List<String> entitledTenants) {
        try {
            Files.createDirectories(installRoot);
            Path staging = Files.createTempDirectory(installRoot, "staging-");
            try {
                extract(tqlapp, staging);
                applyOverlay(staging, overlay);

                AppConfig config = loadConfig(staging);
                String id = config.getString("tesseraql.app.name").orElseThrow(() -> new TqlException(
                        INVALID_PACKAGE, "Package has no tesseraql.app.name: " + tqlapp));
                String version = config.getString("tesseraql.app.version").orElse("0.0.0");

                Path target = installRoot.resolve(id).resolve(version);
                deleteRecursively(target);
                Files.createDirectories(target.getParent());
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);

                InstalledApp app = new InstalledApp(id, version,
                        installRoot.relativize(target).toString().replace('\\', '/'), entitledTenants);
                new AppCatalog(installRoot).register(app);
                return app;
            } finally {
                deleteRecursively(staging);
            }
        } catch (IOException ex) {
            throw new TqlException(INSTALL_ERROR, "Failed to install package: " + ex.getMessage());
        }
    }

    private void extract(Path tqlapp, Path target) throws IOException {
        Path root = target.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(tqlapp))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path resolved = root.resolve(entry.getName()).normalize();
                if (!resolved.startsWith(root)) {
                    throw new TqlException(INVALID_PACKAGE,
                            "Package entry escapes install root (design ch. 20.2): " + entry.getName());
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
