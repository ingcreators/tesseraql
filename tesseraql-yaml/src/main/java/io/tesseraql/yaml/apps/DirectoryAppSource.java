package io.tesseraql.yaml.apps;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import java.nio.file.Files;
import java.nio.file.Path;

/** An {@link AppSource} backed by an existing directory (an unpacked app). */
public final class DirectoryAppSource implements AppSource {

    private static final TqlErrorCode NOT_FOUND = new TqlErrorCode(TqlDomain.YAML, 1203);

    private final String name;
    private final Path directory;

    public DirectoryAppSource(String name, Path directory) {
        this.name = name;
        this.directory = directory.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Path materialize(Path workRoot) {
        if (!Files.isDirectory(directory)) {
            throw new TqlException(NOT_FOUND,
                    "App '" + name + "' directory does not exist: " + directory);
        }
        return directory;
    }
}
