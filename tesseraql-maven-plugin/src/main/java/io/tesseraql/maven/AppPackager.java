package io.tesseraql.maven;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Packages an app home into a deterministic {@code .tqlapp} archive (design ch. 32.3, 48.7).
 *
 * <p>Entries are sorted and given a fixed timestamp so the archive is reproducible; the {@code work}
 * directory and the reserved {@code .tesseraql/} namespace are excluded from the source scan.
 * Build-generated documentation artifacts are merged in under the reserved
 * {@link #GENERATED_DOCS_PREFIX} prefix so the runtime can resolve {@code spec.json} from the
 * mounted app home without the source tree carrying derived files. Excluding source-tree
 * {@code .tesseraql/} keeps run-dependent overlays a later phase may write there (the v2
 * {@code report.json}/{@code history.json}) out of the reproducible archive, regardless of goal
 * ordering — only freshly generated docs enter the reserved namespace.
 */
public final class AppPackager {

    private static final long FIXED_TIME = 0L;

    /** Archive prefix for build-generated documentation artifacts (documentation portal v1). */
    public static final String GENERATED_DOCS_PREFIX = ".tesseraql/docs/";

    /** Packs {@code appHome} into {@code output} (no generated docs merged), returning it. */
    public Path pack(Path appHome, Path output) throws IOException {
        return pack(appHome, null, output);
    }

    /**
     * Packs {@code appHome} into {@code output}, merging the contents of {@code generatedDocs} (the
     * build's {@code tesseraql-generated/docs} directory, if present) under
     * {@link #GENERATED_DOCS_PREFIX}. Returns {@code output}.
     */
    public Path pack(Path appHome, Path generatedDocs, Path output) throws IOException {
        Path home = appHome.toAbsolutePath().normalize();
        Path work = home.resolve("work");
        Path reserved = home.resolve(".tesseraql");
        // Entry name -> source file, sorted by name so the archive order is deterministic across
        // both the source tree and the merged generated docs. The reserved .tesseraql/ namespace is
        // populated only from generatedDocs below, never from the source tree, so run-dependent
        // overlays written there never leak into the reproducible archive.
        TreeMap<String, Path> entries = new TreeMap<>();
        try (var stream = Files.walk(home)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> !path.normalize().startsWith(work))
                    .filter(path -> !path.normalize().startsWith(reserved))
                    .forEach(path -> entries.put(
                            home.relativize(path).toString().replace('\\', '/'), path));
        }
        if (generatedDocs != null && Files.isDirectory(generatedDocs)) {
            Path docs = generatedDocs.toAbsolutePath().normalize();
            try (var stream = Files.walk(docs)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> entries.put(GENERATED_DOCS_PREFIX
                                + docs.relativize(path).toString().replace('\\', '/'), path));
            }
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        try (OutputStream out = Files.newOutputStream(output);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            for (var entry : entries.entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(FIXED_TIME);
                zip.putNextEntry(zipEntry);
                zip.write(Files.readAllBytes(entry.getValue()));
                zip.closeEntry();
            }
        }
        return output;
    }
}
