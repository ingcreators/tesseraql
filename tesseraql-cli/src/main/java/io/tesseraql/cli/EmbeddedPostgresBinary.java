package io.tesseraql.cli;

import java.util.Locale;

/**
 * Maps the running platform to the zonky {@code embedded-postgres-binaries-<os>-<arch>} Maven
 * classifier, so the {@code serve --embedded-db} path can resolve the matching PostgreSQL binary on
 * demand. The five classifiers below are the ones zonky publishes that TesseraQL targets; anything
 * else (e.g. 32-bit, s390x, or musl/alpine linux which needs the separate {@code -alpine} artifact)
 * is rejected with a clear message rather than resolving a binary that cannot run.
 */
final class EmbeddedPostgresBinary {

    private EmbeddedPostgresBinary() {
    }

    /** The classifier for the current JVM's {@code os.name}/{@code os.arch}. */
    static String classifier() {
        return classifier(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    /**
     * The zonky binaries classifier for {@code osName}/{@code osArch}
     * (e.g. {@code linux-amd64}, {@code darwin-arm64v8}), or an {@link IllegalStateException} when
     * the platform has no supported binary.
     */
    static String classifier(String osName, String osArch) {
        String os = os(osName.toLowerCase(Locale.ROOT));
        String arch = arch(osArch.toLowerCase(Locale.ROOT));
        if (os == null || arch == null) {
            throw new IllegalStateException("No embedded PostgreSQL binary for this platform ("
                    + osName + "/" + osArch + "); start an external database and set "
                    + "tesseraql.datasources.main.jdbcUrl instead.");
        }
        // zonky ships windows only for amd64.
        if ("windows".equals(os) && !"amd64".equals(arch)) {
            throw new IllegalStateException("No embedded PostgreSQL binary for " + osName + "/"
                    + osArch + " (Windows ships amd64 only).");
        }
        return os + "-" + arch;
    }

    private static String os(String osName) {
        if (osName.contains("linux")) {
            return "linux";
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return "darwin";
        }
        if (osName.contains("windows")) {
            return "windows";
        }
        return null;
    }

    private static String arch(String osArch) {
        if (osArch.equals("amd64") || osArch.equals("x86_64") || osArch.equals("x64")) {
            return "amd64";
        }
        if (osArch.equals("aarch64") || osArch.equals("arm64")) {
            return "arm64v8";
        }
        return null;
    }
}
