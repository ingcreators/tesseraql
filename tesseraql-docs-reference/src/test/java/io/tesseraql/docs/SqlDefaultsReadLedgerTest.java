package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The three app-wide SQL bounds are read in one place, and every executor is given them.
 *
 * <p>{@code tesseraql.sql.timeoutSeconds} was consolidated into {@link
 * io.tesseraql.yaml.config.SqlDefaults} for a recorded reason: it had been read at six sites with
 * three different default expressions, so the bound an executor ran under depended on which
 * executor asked. Its two siblings — the row ceiling and the overflow policy — were left behind,
 * and drifted further than the timeout ever had: one executor did not read them at all, so the
 * same job capped at ten thousand rows under {@code tesseraql job run} and at whatever the
 * application configured under the served runtime.
 *
 * <p>Two assertions, because one cannot see the other's defect. The first pins each key to its
 * single reader; it cannot see a site that ignores the key entirely, because a site that never
 * names a key is invisible to a scan for that key. The second walks the executor's construction
 * sites instead.
 *
 * <p><b>What neither sees.</b> A changed value in {@code SqlStatement} keeps both green — that is
 * {@code SqlStatementTest}'s job. A second construction inside a file that already wires the bound
 * is invisible, because the second pattern is file-scoped, the same limit
 * {@code ContractReadBoundLedgerTest} records. And a literal restatement that never names the key
 * is invisible to the first assertion by construction.
 */
class SqlDefaultsReadLedgerTest {

    private static final Path REPO = Path.of("..");

    /** The one file allowed to turn any of these keys into a value. */
    private static final String THE_READER = "tesseraql-yaml/src/main/java/io/tesseraql/yaml/config/SqlDefaults.java";

    private static final List<String> APP_WIDE_BOUNDS = List.of(
            "tesseraql.sql.timeoutSeconds",
            "tesseraql.resultMaterialization.maxRows",
            "tesseraql.resultMaterialization.onOverflow");

    private static final Pattern CONSTRUCTS = Pattern.compile("new JobExecutor\\(");
    private static final Pattern WIRES_BOUND = Pattern.compile("\\.resultBounds\\(");

    /**
     * Every file the published configuration reference reports as reading one of these keys is
     * {@code SqlDefaults}.
     *
     * <p>The expected set is non-empty on purpose: a scan that found nothing at all would satisfy
     * an "is empty" assertion on the difference, and report a dead walk as a clean tree.
     */
    @Test
    void everyReadOfTheAppWideSqlBoundsIsInSqlDefaults() throws IOException {
        for (String key : APP_WIDE_BOUNDS) {
            assertThat(ConfigReference.readersOf(REPO, key))
                    .as("files reading %s; the app-wide bounds resolve once, in SqlDefaults, so"
                            + " two executors cannot default apart", key)
                    .containsExactly(THE_READER);
        }
    }

    /**
     * Every {@code JobExecutor} built where a configuration is in reach is given its row bound.
     *
     * <p>A job that ignores {@code tesseraql.resultMaterialization.maxRows} caps differently from
     * the served runtime running the same job — which is what happened, unnoticed, because no
     * assertion looked at the construction site.
     */
    @Test
    void everyJobExecutorIsBuiltWithItsResultBounds() throws IOException {
        Set<String> constructs = new TreeSet<>();
        Set<String> unwired = new TreeSet<>();
        for (Path path : mainSources()) {
            String source = Files.readString(path);
            if (!CONSTRUCTS.matcher(source).find()) {
                continue;
            }
            constructs.add(relative(path));
            if (!WIRES_BOUND.matcher(source).find()) {
                unwired.add(relative(path));
            }
        }

        // The sensitivity line first: without it, a walk that found no construction site at all
        // would report an empty unwired set and read as a pass.
        assertThat(constructs)
                .as("files constructing a JobExecutor; if this set changes, the assertion below"
                        + " is looking at a different tree than the one it was written for")
                .containsExactlyInAnyOrder(
                        "tesseraql-cli/src/main/java/io/tesseraql/cli/JobCommand.java",
                        "tesseraql-runtime/src/main/java/io/tesseraql/runtime/"
                                + "TesseraqlRuntime.java");

        assertThat(unwired)
                .as("files building a JobExecutor without giving it resultBounds. The"
                        + " configuration is in reach at both sites, so the bound is passed at"
                        + " both: a job must not cap differently depending on how it was started")
                .isEmpty();
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(REPO)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    // A .claude/ worktree on disk carries stale copies of exactly these files.
                    .filter(path -> !path.toString().contains("/."))
                    .toList();
        }
    }

    private static String relative(Path path) {
        return REPO.relativize(path).toString().replace('\\', '/');
    }
}
