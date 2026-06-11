package io.tesseraql.core.dialect;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resolves a SQL file to its dialect-specific variant when one exists (design ch. 42.3).
 *
 * <p>For a base file {@code search.sql} and dialect {@code mysql}, a sibling {@code search.mysql.sql}
 * is preferred; otherwise the base file is used. This lets an app override only the statements that
 * differ between databases.
 */
public final class DialectSqlResolver {

    private DialectSqlResolver() {
    }

    /** Returns the dialect-specific variant of {@code baseFile} if present, else {@code baseFile}. */
    public static Path resolve(Path baseFile, String dialect) {
        if (dialect == null || dialect.isBlank()) {
            return baseFile;
        }
        String fileName = baseFile.getFileName().toString();
        int dot = fileName.lastIndexOf(".sql");
        if (dot < 0) {
            return baseFile;
        }
        String variantName = fileName.substring(0, dot) + "." + dialect + ".sql";
        Path variant = baseFile.resolveSibling(variantName);
        return Files.isRegularFile(variant) ? variant : baseFile;
    }
}
