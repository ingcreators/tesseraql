package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * An {@link AppSource} that ships an app as classpath resources (a jar-bundled system app) and
 * extracts it under the runtime work directory at boot.
 *
 * <p>Because classpath directories cannot be listed portably across jars, the resource root must
 * contain an index file ({@value #INDEX}) listing the app's files as one relative path per line
 * (blank lines and {@code #} comments ignored). The index is authored alongside the app so the
 * extracted tree is deterministic (design ch. 48). Entries are confined to the extraction
 * directory; {@code ..} traversal is rejected.
 */
public final class ClasspathAppSource implements AppSource {

    public static final String INDEX = ".app-index";

    private static final TqlErrorCode MISSING = new TqlErrorCode(TqlDomain.YAML, 1204);
    private static final TqlErrorCode TRAVERSAL = new TqlErrorCode(TqlDomain.YAML, 1201);

    private final String name;
    private final String resourceRoot;
    private final ClassLoader classLoader;

    /** @param resourceRoot the classpath directory holding the app (no trailing slash) */
    public ClasspathAppSource(String name, String resourceRoot, ClassLoader classLoader) {
        this.name = name;
        this.resourceRoot = resourceRoot.endsWith("/")
                ? resourceRoot.substring(0, resourceRoot.length() - 1)
                : resourceRoot;
        this.classLoader = classLoader;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path materialize(Path workRoot) {
        Path target = workRoot.resolve(name).normalize();
        try {
            Files.createDirectories(target);
            for (String entry : readIndex()) {
                Path file = target.resolve(entry).normalize();
                if (!file.startsWith(target)) {
                    throw new TqlException(TRAVERSAL,
                            "App '" + name + "' index entry escapes the app root: " + entry);
                }
                Files.createDirectories(file.getParent());
                try (InputStream in = require(resourceRoot + "/" + entry)) {
                    Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return target;
    }

    private java.util.List<String> readIndex() throws IOException {
        try (InputStream in = require(resourceRoot + "/" + INDEX)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
    }

    private InputStream require(String resource) {
        InputStream in = classLoader.getResourceAsStream(resource);
        if (in == null) {
            throw new TqlException(MISSING,
                    "App '" + name + "' resource not found on classpath: " + resource);
        }
        return in;
    }
}
