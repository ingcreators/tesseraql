package io.tesseraql.maven;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages an app home into a deterministic {@code .tqlapp} archive (design ch. 32.3, 48.7).
 *
 * <p>Entries are sorted and given a fixed timestamp so the archive is reproducible; the {@code work}
 * directory is excluded.
 */
public final class AppPackager {

    private static final long FIXED_TIME = 0L;

    /** Packs {@code appHome} into {@code output}, returning {@code output}. */
    public Path pack(Path appHome, Path output) throws IOException {
        Path home = appHome.toAbsolutePath().normalize();
        Path work = home.resolve("work");
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(home)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.normalize().startsWith(work))
                    .sorted()
                    .forEach(files::add);
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (OutputStream out = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path file : files) {
                String name = home.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(FIXED_TIME);
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return output;
    }
}
