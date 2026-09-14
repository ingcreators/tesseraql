package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No main source describes the CLI by a verb it no longer has
 * (docs/audit-medium-leads.md slice 10, F112). {@code serve} became {@code dev} in 0.15.0
 * (#846); the two user-facing sites were fixed and guarded by #1211 and #1244, and twenty-two
 * Javadoc and comment sentences kept teaching {@code serve --embedded-db},
 * {@code serve --watch} and "the serve command" — read by the next contributor, and by the
 * Javadoc that ships. The one sentence that names {@code serve} as history ("it replaces
 * {@code serve}") is the exception the pattern leaves alone.
 */
class RetiredVerbLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Pattern RETIRED = Pattern.compile(
            "\\{@code serve( --[a-z-]+)?\\}|`serve( --[a-z-]+)?`|\\bserve --(embedded-db|watch)"
                    + "|\\bserve( |-)command");

    @Test
    void noMainSourceTeachesTheRetiredServeVerb() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = file.toString().replace('\\', '/');
                if (!path.endsWith(".java") || !path.contains("/src/main/java/")
                        || path.contains("/target/") || path.contains("/.claude/")
                        || path.contains("/work/")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.contains("replaces {@code serve}")) {
                        continue;
                    }
                    if (RETIRED.matcher(line).find()) {
                        offenders.add(REPO.relativize(file) + ":" + (i + 1) + ": " + line.strip());
                    }
                }
            }
        }
        assertThat(offenders).as("main-source lines describing the CLI by `serve`").isEmpty();
    }
}
