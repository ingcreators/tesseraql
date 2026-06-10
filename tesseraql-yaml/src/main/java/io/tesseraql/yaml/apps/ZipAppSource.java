package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * An {@link AppSource} that mounts a {@code .tqlapp} package directly (design ch. 32.3): the zip
 * is extracted under the runtime work directory at boot, so a packaged app can be listed as
 * {@code tesseraql.apps.<name>.package: <path>.tqlapp} without a separate install step.
 *
 * <p>The extraction target is cleaned first (the package is the source of truth, so files removed
 * between versions disappear) and every entry is path-confined (zip-slip safe, ch. 20.2).
 */
public final class ZipAppSource implements AppSource {

    private static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.YAML, 1203);
    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.YAML, 1201);

    private static final TqlErrorCode INTEGRITY = new TqlErrorCode(TqlDomain.YAML, 1210);

    private final String name;
    private final Path packageFile;
    private final String expectedSha256;

    public ZipAppSource(String name, Path packageFile) {
        this(name, packageFile, null);
    }

    /** With a non-null {@code expectedSha256} the package hash is verified before extraction. */
    public ZipAppSource(String name, Path packageFile, String expectedSha256) {
        this.name = name;
        this.packageFile = packageFile.toAbsolutePath().normalize();
        this.expectedSha256 = expectedSha256;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path materialize(Path workRoot) {
        if (!Files.isRegularFile(packageFile)) {
            throw new TqlException(NOT_FOUND,
                    "App '" + name + "' package does not exist: " + packageFile);
        }
        if (expectedSha256 != null && !expectedSha256.isBlank()) {
            String actual = io.tesseraql.core.util.Hashing.sha256(packageFile);
            if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                throw new TqlException(INTEGRITY, "App '" + name
                        + "' package integrity check failed: expected sha256 " + expectedSha256
                        + " but was " + actual);
            }
        }
        Path target = workRoot.resolve(name).normalize();
        try {
            deleteRecursively(target);
            Files.createDirectories(target);
            try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(packageFile))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path resolved = target.resolve(entry.getName()).normalize();
                    if (!resolved.startsWith(target)) {
                        throw new TqlException(TRAVERSAL,
                                "App '" + name + "' package entry escapes the app root: "
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
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return target;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
