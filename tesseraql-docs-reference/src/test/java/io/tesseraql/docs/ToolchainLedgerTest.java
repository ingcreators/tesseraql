package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The host's toolchain is CI's (docs/host-development.md decision 2). {@code mise.toml} pins what a
 * contributor's machine builds with; the {@code setup-java} and {@code setup-node} steps pin what
 * CI builds with; nothing but this test holds the two together, because CI does not read
 * {@code mise.toml}. The Dev Container this replaces carried a different JDK vendor than CI for
 * as long as it existed, and nobody chose that.
 */
class ToolchainLedgerTest {

    private static final Path REPO = Path.of("..");
    private static final Path WORKFLOWS = REPO.resolve(".github/workflows");

    private static final Pattern MISE_JAVA = Pattern
            .compile("(?m)^java\\s*=\\s*\"temurin-(\\d+)\"");
    private static final Pattern MISE_NODE = Pattern.compile("(?m)^node\\s*=\\s*\"(\\d+)\"");
    private static final Pattern JAVA_VERSION = Pattern
            .compile("^\\s*java-version:\\s*'?(\\d+|\\$\\{\\{ matrix\\.java }})'?\\s*$");
    private static final Pattern MATRIX_JAVA_ENTRY = Pattern.compile("^\\s*-\\s*'(\\d+)'\\s*$");
    private static final Pattern NODE_VERSION = Pattern
            .compile("^\\s*node-version:\\s*'?(\\d+)'?\\s*$");

    @Test
    void theJavaMajorIsTheReleaseAndEverySetupJavaStep() throws IOException {
        String mise = Files.readString(REPO.resolve("mise.toml"));
        Matcher java = MISE_JAVA.matcher(mise);
        assertThat(java.find()).as("mise.toml pins java as temurin-<major>, CI's distribution")
                .isTrue();
        String major = java.group(1);

        Matcher release = Pattern.compile("<maven.compiler.release>(\\d+)</")
                .matcher(Files.readString(REPO.resolve("pom.xml")));
        assertThat(release.find()).isTrue();
        assertThat(release.group(1)).as("maven.compiler.release").isEqualTo(major);

        List<String> ci = javaMajorsInWorkflows();
        assertThat(ci).as("setup-java steps under .github/workflows").isNotEmpty()
                .allSatisfy(version -> assertThat(version).isEqualTo(major));
    }

    @Test
    void theNodeMajorIsEverySetupNodeStep() throws IOException {
        Matcher node = MISE_NODE.matcher(Files.readString(REPO.resolve("mise.toml")));
        assertThat(node.find()).as("mise.toml pins node as its major").isTrue();

        List<String> ci = new ArrayList<>();
        for (Path workflow : workflows()) {
            for (String line : Files.readAllLines(workflow)) {
                Matcher version = NODE_VERSION.matcher(line);
                if (version.matches()) {
                    ci.add(workflow.getFileName() + ": " + version.group(1));
                }
            }
        }
        assertThat(ci).as("setup-node steps under .github/workflows").isNotEmpty()
                .allSatisfy(entry -> assertThat(entry).endsWith(": " + node.group(1)));
    }

    /**
     * Without {@code unzip} the wrapper downloads a different file and fails its pinned checksum
     * as a possible compromise (PluginVersionLedgerTest has the whole story); the container
     * installed it with apt, and a host declares it where mise can apply it.
     */
    @Test
    void theHostCarriesUnzipForTheWrapper() throws IOException {
        String mise = Files.readString(REPO.resolve("mise.toml"));
        String packages = mise.substring(mise.indexOf("[bootstrap.packages]"));
        assertThat(packages).as("mise.toml [bootstrap.packages]")
                .containsPattern("(?m)^\"apt:unzip\"\\s*=");
    }

    /** Every {@code java-version} a workflow sets, a matrix reference expanded to its entries. */
    private static List<String> javaMajorsInWorkflows() throws IOException {
        List<String> majors = new ArrayList<>();
        for (Path workflow : workflows()) {
            List<String> lines = Files.readAllLines(workflow);
            for (int i = 0; i < lines.size(); i++) {
                Matcher version = JAVA_VERSION.matcher(lines.get(i));
                if (!version.matches()) {
                    continue;
                }
                if (!version.group(1).startsWith("$")) {
                    majors.add(version.group(1));
                    continue;
                }
                majors.addAll(matrixJava(lines));
            }
        }
        return majors;
    }

    /** The entries under a workflow's {@code matrix: java:} list. */
    private static List<String> matrixJava(List<String> lines) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).strip().equals("java:")) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                Matcher entry = MATRIX_JAVA_ENTRY.matcher(lines.get(j));
                if (!entry.matches()) {
                    break;
                }
                entries.add(entry.group(1));
            }
        }
        assertThat(entries).as("a matrix.java reference with no matrix java entries").isNotEmpty();
        return entries;
    }

    private static List<Path> workflows() throws IOException {
        try (Stream<Path> files = Files.list(WORKFLOWS)) {
            return files.filter(file -> file.toString().endsWith(".yml")).sorted().toList();
        }
    }
}
