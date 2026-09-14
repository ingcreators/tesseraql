package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every codec discovery names its loader, and the ledger says which loader each one may name
 * (docs/codec-discovery.md decision 1). A runtime discovers once, on its application's module
 * loader, and hands the instance on; a CLI verb that composed the thread context loader over
 * the application's modules may read that loader — and nothing else may, because a discovery
 * on the context loader inside a hosted runtime is exactly how the synchronous export route
 * came to refuse a codec the transfer service was serving.
 *
 * <p>The ledger is closed both ways: a discovery outside it fails, and a row whose site no
 * longer discovers fails too, so the list describes the tree rather than its history.
 */
class CodecDiscoveryLedgerTest {

    private static final Path REPO = Path.of("..");

    /** The start of {@code FileCodecs.discover(}, the call possibly wrapped after the class name. */
    private static final Pattern DISCOVERY = Pattern.compile("FileCodecs\\s*\\.discover\\(");

    /**
     * Each main-source site that discovers, and the loader expression it discovers on. The
     * context loader appears exactly where a CLI verb composed it (docs/cli-surface.md) and in
     * the linter's two-argument default, which the CLI's lint verb calls after composing it;
     * a class's own loader is a unit-test default a runtime always overrides.
     */
    private static final Map<String, List<String>> LEDGER = Map.of(
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/AppModules.java",
            // The module loader, or the runtime's own when an application has no modules.
            List.of("loader", "AppModules.class.getClassLoader()"),
            "tesseraql-compiler/src/main/java/io/tesseraql/compiler/RouteCompiler.java",
            List.of("RouteCompiler.class.getClassLoader()"),
            "tesseraql-studio/src/main/java/io/tesseraql/studio/StudioService.java",
            List.of("StudioService.class.getClassLoader()"),
            "tesseraql-cli/src/main/java/io/tesseraql/cli/JobCommand.java",
            List.of("Thread.currentThread().getContextClassLoader()"),
            // The MCP dev tools serve several applications, each on its own module loader.
            "tesseraql-cli/src/main/java/io/tesseraql/cli/mcp/McpDevTools.java",
            List.of("loaders.get(name)"),
            "tesseraql-yaml/src/main/java/io/tesseraql/yaml/lint/AppLinter.java",
            List.of("Thread.currentThread().getContextClassLoader()"));

    @Test
    void everyCodecDiscoveryIsALedgerRowNamingItsLoader() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String path = REPO.relativize(file).toString().replace('\\', '/');
                if (!path.endsWith(".java") || !path.contains("/src/main/java/")
                        || path.contains("/target/") || path.startsWith(".claude/")
                        || path.startsWith("work/")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher discovery = DISCOVERY.matcher(source);
                while (discovery.find()) {
                    String argument = argument(source, discovery.end());
                    List<String> expected = LEDGER.get(path);
                    if (expected == null) {
                        offenders.add(path + ": discovers on '" + argument
                                + "' but is not in the ledger");
                    } else if (!expected.contains(argument)) {
                        offenders.add(path + ": discovers on '" + argument + "', the ledger says "
                                + expected);
                    }
                    seen.add(path);
                }
                // The no-argument overload is gone (decision 1); a site that reads the context
                // loader spells it, so the shape stays visible at the call.
                assertThat(source).as("%s: a discovery with no loader named", path)
                        .doesNotContain("FileCodecs.discover()");
            }
        }
        assertThat(offenders).as("codec discoveries outside the ledger").isEmpty();
        for (String site : LEDGER.keySet()) {
            assertThat(seen).as("the ledger row %s no longer discovers; drop the row", site)
                    .contains(site);
        }
    }

    /** The call's argument text from {@code from} to its closing parenthesis, whitespace removed. */
    private static String argument(String source, int from) {
        int depth = 1;
        int at = from;
        while (at < source.length() && depth > 0) {
            char c = source.charAt(at);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            at++;
        }
        return source.substring(from, at - 1).replaceAll("\\s+", "");
    }
}
