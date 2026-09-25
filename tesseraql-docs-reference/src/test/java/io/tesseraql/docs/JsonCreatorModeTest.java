package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every {@code @JsonCreator} in the main sources names its mode (docs/jackson-3.md decision 8).
 * Jackson 3 builds in parameter names, so a creator whose single argument carries a name is read
 * as a properties creator: {@code LockSpec.of(String column)} — the {@code lock: version}
 * shorthand — stopped accepting its scalar, and eight tests went red for it. A mode left to
 * Jackson's heuristics is a reading that can move with a release; declared, it cannot.
 */
class JsonCreatorModeTest {

    private static final Path REPO = Path.of("..");

    /** The annotation as code, plain or qualified; a mention in a comment is not one. */
    private static final Pattern CREATOR = Pattern.compile(
            "(?m)^\\s*@(?:com\\.fasterxml\\.jackson\\.annotation\\.)?JsonCreator\\b"
                    + "(\\s*\\(\\s*mode\\s*=)?");

    @Test
    void everyCreatorDeclaresItsMode() throws IOException {
        Set<String> bare = new TreeSet<>();
        int creators = 0;
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().contains("/src/main/java/"))
                    .filter(p -> !p.toString().contains("/target/"))
                    // Hidden directories are never main sources (a .claude/ worktree holds copies).
                    .filter(p -> !p.toString().contains("/."))
                    .toList()) {
                String source = read(path);
                Matcher creator = CREATOR.matcher(source);
                while (creator.find()) {
                    creators++;
                    if (creator.group(1) == null) {
                        int line = (int) source.substring(0, creator.start()).lines().count() + 1;
                        bare.add(REPO.relativize(path).toString().replace('\\', '/') + ":" + line);
                    }
                }
            }
        }
        assertThat(creators).as("the scan found the creators at all").isGreaterThanOrEqualTo(9);
        assertThat(bare)
                .as("@JsonCreator without mode = DELEGATING or PROPERTIES")
                .isEmpty();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
