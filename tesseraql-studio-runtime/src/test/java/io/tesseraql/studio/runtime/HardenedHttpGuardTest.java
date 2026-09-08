package io.tesseraql.studio.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every request this module's suites make goes through {@link TestHttp}.
 *
 * <p>A plain {@code HttpClient} has no request timeout. A response the runtime never writes then
 * blocks the test thread until something else gives up — and this module has nothing else: no
 * {@code src/test/resources}, so no {@code junit-platform.properties}, so no default test timeout,
 * and no surefire block in its pom. The runner hangs, and the failure it eventually reports is the
 * client's side of a story whose interesting half is the server's. That is issue #341, twice.
 *
 * <p>{@code TestHttp} is the door: a 30-second request timeout, and a trip dumps every thread in
 * the JVM before failing — the runtime under test lives in the same process, so the dump shows
 * what the server was not doing.
 *
 * <p><b>Why construction and not the send.</b> Keying this on the no-arg factory call followed by
 * a send would be green on {@code StackStudioIntegrationTest}'s builder form, which is one of the
 * sites it exists to catch. Measured, not assumed: that variant was built and run first, and it
 * missed exactly that site.
 *
 * <p><b>Why comments are not stripped.</b> A comment naming a constructor trips this too, and that
 * is the safe direction — a guard that fires wrongly gets read, a guard that stops firing gets
 * trusted. Which is why the needles below are assembled rather than spelled: this class would
 * otherwise report itself, and the answer to that is to write code the guard does not trip, never
 * a second exemption. {@code TestHttp} is the only file exempt, and it is the door itself.
 */
class HardenedHttpGuardTest {

    private static final Path TESTS = Path.of("src/test/java");

    /** The door itself: the one file allowed to construct a client. */
    private static final String DOOR = "TestHttp.java";

    /**
     * Assembled, not spelled, so this file does not match its own scan. Both forms: the codebase
     * uses the no-arg factory in twelve places and the builder in two, and a needle for only the
     * first is blind to the second.
     */
    private static final List<String> CONSTRUCTIONS = List.of(
            "HttpClient" + ".newHttpClient(", "HttpClient" + ".newBuilder(");

    @Test
    void noSuiteBuildsItsOwnHttpClient() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals(DOOR))
                    .sorted()
                    .forEach(path -> collect(path, found));
        }

        assertThat(found)
                .as("an unbounded HTTP client in a module with no default test timeout is how"
                        + " issue #341 hung the runner twice. Send through TestHttp, which carries"
                        + " a 30s request timeout and dumps every JVM thread when it trips")
                .isEmpty();
    }

    private static void collect(Path path, List<String> found) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                final String line = lines.get(i);
                if (CONSTRUCTIONS.stream().anyMatch(line::contains)) {
                    found.add(path.getFileName() + ":" + (i + 1));
                }
            }
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
