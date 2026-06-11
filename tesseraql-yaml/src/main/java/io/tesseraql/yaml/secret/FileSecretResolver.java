package io.tesseraql.yaml.secret;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The built-in {@code file} provider: {@code ${secret.file.db_password}} reads the trimmed content
 * of {@code <secrets-dir>/db_password} - the Kubernetes/Docker secrets mount convention. The
 * directory defaults to {@code /run/secrets} and is overridden with the
 * {@code tesseraql.secrets.dir} system property or the {@code TESSERAQL_SECRETS_DIR} environment
 * variable. Names are confined to the directory (no path separators or traversal).
 */
public final class FileSecretResolver implements SecretResolver {

    private final Path directory;

    public FileSecretResolver() {
        this(defaultDirectory());
    }

    public FileSecretResolver(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    private static Path defaultDirectory() {
        String fromProperty = System.getProperty("tesseraql.secrets.dir");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return Path.of(fromProperty);
        }
        String fromEnv = System.getenv("TESSERAQL_SECRETS_DIR");
        return Path.of(fromEnv == null || fromEnv.isBlank() ? "/run/secrets" : fromEnv);
    }

    @Override
    public String provider() {
        return "file";
    }

    @Override
    public String resolve(String name) {
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory) || !file.getParent().equals(directory)
                || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file).trim();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
