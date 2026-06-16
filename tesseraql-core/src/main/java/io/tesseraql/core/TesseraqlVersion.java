package io.tesseraql.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The single source of the framework version, read from a build-filtered classpath resource
 * (filled from the reactor {@code project.version}). Code references this instead of hardcoding the
 * version, so a release ({@code versions:set}) updates every surface — the CLI {@code --version},
 * the embedded resolver's BOM coordinate, the scaffolded wrapper POM — with no manual edits.
 */
public final class TesseraqlVersion {

    private static final String VERSION = load();

    private TesseraqlVersion() {
    }

    /** The framework version (e.g. {@code 0.2.0} or {@code 0.2.0-SNAPSHOT}). */
    public static String current() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = TesseraqlVersion.class
                .getResourceAsStream("/io/tesseraql/core/version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "unknown");
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
