package io.tesseraql.yaml.scaffold;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * What {@code tesseraql new} writes must be true of what {@code tesseraql new} writes.
 *
 * <p>A generated application is where a reader meets this framework, and its text drifted from the
 * framework it describes twice over. The welcome page said {@code mvn} while the same scaffolder
 * wrote {@code mvnw} and {@code mvnw.cmd} beside it. The README routed every new application
 * through a GitHub Packages token and linked to a documentation section that no longer says any
 * such thing — the barrier #1229 removed from the framework's own pages one release earlier.
 *
 * <p>Asserted against the scaffolder's <b>output</b>, not against the committed gallery: it fails
 * at the source, it needs no Docker, and it does not depend on the gallery having been regenerated.
 * The gallery's own drift is already covered by {@code ScaffoldDogfoodIntegrationTest}, which
 * compares it byte for byte.
 *
 * <p>This asserts nothing about how an application developer builds — that is theirs to invoke, and
 * {@code PluginVersionLedgerTest} deliberately scopes its own sweep to the framework's scripts for
 * exactly that reason. It asserts only that framework-authored generated text is consistent with
 * the framework-authored files two directories over.
 */
class ScaffoldedInstructionsTest {

    private final AppScaffolder scaffolder = new AppScaffolder();

    /**
     * A Maven invocation that is not the wrapper's.
     *
     * <p>Deliberately looser than a word match on {@code mvn }: the generated welcome page writes
     * its commands inside {@code <code>} elements, so a predicate demanding whitespace after
     * {@code mvn} goes green the day someone writes {@code <code>mvn</code> tesseraql:test}. This
     * matches {@code mvn} at a token boundary followed by whitespace, a tag, or end of line, and
     * excludes {@code mvnw}, {@code ./mvnw} and {@code mvnw.cmd}.
     */
    private static final Pattern BARE_MVN = Pattern.compile("(^|[^-./\\w])mvn(?![\\w.])(\\s|<|$)",
            Pattern.MULTILINE);

    @Test
    void aGeneratedApplicationNamesTheWrapperItShips() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");
        assertThat(files).as("the scaffolder produced nothing to check").isNotEmpty();
        assertThat(files).extracting(ScaffoldedFile::path).contains("mvnw", "mvnw.cmd");

        List<String> bare = new ArrayList<>();
        for (ScaffoldedFile file : files) {
            // The wrapper scripts are Maven's own and name the tool throughout; they are what the
            // instructions are supposed to point AT.
            if (file.path().equals("mvnw") || file.path().equals("mvnw.cmd")) {
                continue;
            }
            file.content().lines().forEach(line -> {
                if (BARE_MVN.matcher(line).find()) {
                    bare.add(file.path() + ": " + line.trim());
                }
            });
        }

        assertThat(bare)
                .as("a generated application tells its author to run a Maven off the PATH while "
                        + "the same scaffolder ships mvnw and mvnw.cmd beside it")
                .isEmpty();
    }

    @Test
    void aGeneratedApplicationAsksForNoCredentialsItDoesNotNeed() {
        List<ScaffoldedFile> files = scaffolder.scaffold("demo-app");
        assertThat(files).as("the scaffolder produced nothing to check").isNotEmpty();

        List<String> asking = new ArrayList<>();
        for (ScaffoldedFile file : files) {
            file.content().lines().forEach(line -> {
                if (line.contains("read:packages") || line.contains("maven.pkg.github")) {
                    asking.add(file.path() + ": " + line.trim());
                }
            });
        }

        assertThat(asking)
                .as("a generated application asks its author to mint a GitHub Packages token; the "
                        + "framework's artifacts are on Maven Central and nothing needs configuring")
                .isEmpty();
    }
}
