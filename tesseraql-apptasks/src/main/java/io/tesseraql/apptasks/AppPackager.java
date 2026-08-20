package io.tesseraql.apptasks;

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
 * ordering — only freshly generated docs and the lock-verified module closure enter the reserved
 * namespace.
 */
public final class AppPackager {

    private static final long FIXED_TIME = 0L;

    /** Archive prefix for build-generated documentation artifacts (documentation portal v1). */
    public static final String GENERATED_DOCS_PREFIX = ".tesseraql/docs/";

    /** Archive prefix for the module set the package carries (docs/module-channel.md decision 3). */
    public static final String MODULES_PREFIX = io.tesseraql.yaml.config.WorkHome.BUNDLED_MODULES
            + "/";

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
        return pack(appHome, generatedDocs, null, output);
    }

    /**
     * Like {@link #pack(Path, Path, Path)}, but also carries the jars in {@code modulesDir} — the
     * closure the caller resolved from {@code modules.lock} — under {@link #MODULES_PREFIX}, so an
     * installed application has the modules it declared without a resolver on the deployment
     * machine (docs/module-channel.md decision 3). A null or empty directory packs nothing extra,
     * which is the shape of an application that declares no modules.
     *
     * <p>Reproducibility rests on the lock rather than on the directory: the jars come from a work
     * tree, and what makes two builds of the same commit produce the same archive is that
     * {@link PackagedModules} verified the closure against {@code modules.lock} first.
     */
    public Path pack(Path appHome, Path generatedDocs, Path modulesDir, Path output)
            throws IOException {
        Path home = appHome.toAbsolutePath().normalize();
        Path work = io.tesseraql.yaml.config.WorkHome.resolve(home,
                io.tesseraql.yaml.manifest.ManifestLoader.configOnly(home));
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
        if (modulesDir != null && Files.isDirectory(modulesDir)) {
            try (var stream = Files.list(modulesDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".jar"))
                        .forEach(path -> entries.put(
                                MODULES_PREFIX + path.getFileName(), path));
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
