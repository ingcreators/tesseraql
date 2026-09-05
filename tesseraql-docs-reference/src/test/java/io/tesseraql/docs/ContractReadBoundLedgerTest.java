package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The identity row-bound ledger (docs/contract-source-seam.md), in the
 * {@code WorkHomeLedgerTest} shrink-only shape: the two ways an identity contract read can still
 * run without the bound {@code tesseraql.identity.maxRows} sets.
 *
 * <p>Identity's reads had no bound at all — sign-in, principal resolution, role and access
 * administration, invitations, recovery, the account app and the {@code iam.*} providers all
 * materialized whatever the store returned. They are bounded now, and this records what the bound
 * does <b>not</b> reach. Every entry is a recorded GAP, not an approval; repairing one instance of
 * a class silently is how the class survives.
 *
 * <p><b>Why an exemption is dangerous enough to guard.</b> A read that escapes the bound is a read
 * that can materialize without limit on a request path. The one exemption that exists is on the
 * sign-in path and is exempt precisely because bounding it would be worse — but a second one added
 * without argument would be the defect this campaign closed, growing back. So the exemption is a
 * private method with a single call site, and this test fails when a second appears.
 *
 * <p>This ledger cannot see everything, and says so: it counts call sites and construction sites,
 * not row counts. Whether a bounded read is bounded at a sensible NUMBER is
 * {@code IdentityRealmDialectTest}'s question, and whether a refusal stays distinguishable from an
 * absent feature is {@code IdentityServiceIntegrationTest}'s.
 */
class ContractReadBoundLedgerTest {

    private static final Path REPO = Path.of("..");

    /**
     * A call to the unbounded read, as distinct from its declaration. The declaration reads
     * {@code executeUnbounded(RealmConfig realm, …)}; a call passes the realm it already holds.
     */
    private static final Pattern EXEMPTION_CALL = Pattern.compile("executeUnbounded\\(realm,");

    /** An identity service being constructed, wherever it happens. */
    private static final Pattern CONSTRUCTS = Pattern.compile("new IdentityService\\(");

    /** That construction being given its row bound. */
    private static final Pattern WIRES_BOUND = Pattern.compile("\\.resultMaxRows\\(");

    /**
     * The reads that deliberately run with no bound. Exactly one, and it is on the sign-in path:
     * {@code resolvePrincipal} evaluates the enabled assignment rules app-wide, with no user
     * predicate, on every managed sign-in — and its caller rethrows anything that is not a missing
     * contract. A bound there turns ordinary rule growth into an authentication outage for every
     * user at once, so the cap is the wrong instrument. The right repair is that the read is
     * unfiltered on a request path at all, and that is filed as its own slice.
     */
    private static final int RECORDED_EXEMPTIONS = 1;

    /**
     * Construction sites that cannot wire the bound, because they hold no application config to
     * read {@code tesseraql.identity.maxRows} from. Each is a recorded gap: an identity service
     * built here reads unbounded.
     *
     * <ul>
     * <li>{@code IdentityBootstrap} — reached from {@code tql identity schema} and the Maven
     * goal, both of which run against a datasource with no application loaded.</li>
     * <li>{@code AppTestRunner} — {@code tql test}, which builds its own service for a suite.</li>
     * <li>{@code StudioTestService} — Studio's sandbox runner, which caps its own reads at the
     * driver instead ({@code SandboxDataSource} sets {@code setMaxRows}).</li>
     * </ul>
     */
    private static final Set<String> UNBOUNDED_CONSTRUCTIONS = new TreeSet<>(List.of(
            "tesseraql-apptasks/src/main/java/io/tesseraql/apptasks/IdentityBootstrap.java",
            "tesseraql-report/src/main/java/io/tesseraql/report/AppTestRunner.java",
            "tesseraql-studio-runtime/src/main/java/io/tesseraql/studio/runtime/"
                    + "StudioTestService.java"));

    @Test
    void theUnboundedReadHasExactlyTheRecordedExemptions() throws IOException {
        int calls = 0;
        Set<String> files = new TreeSet<>();
        for (Path path : mainSources()) {
            Matcher matcher = EXEMPTION_CALL.matcher(Files.readString(path));
            while (matcher.find()) {
                calls++;
                files.add(relative(path));
            }
        }

        assertThat(calls)
                .as("calls to the unbounded identity read. This ledger is shrink-only: a new"
                        + " exemption is a read that can materialize without limit on a request"
                        + " path, which is the defect this campaign closed. Bound the read, or"
                        + " add it here in review with the reason a bound would be worse")
                .isEqualTo(RECORDED_EXEMPTIONS);
        assertThat(files)
                .as("the exemption is private to IdentityService so that it cannot be reached"
                        + " from another module")
                .containsExactly(
                        "tesseraql-identity/src/main/java/io/tesseraql/identity/"
                                + "IdentityService.java");
    }

    @Test
    void everyIdentityServiceBuiltWithoutTheBoundIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path path : mainSources()) {
            String source = Files.readString(path);
            if (CONSTRUCTS.matcher(source).find() && !WIRES_BOUND.matcher(source).find()) {
                found.add(relative(path));
            }
        }

        assertThat(found)
                .as("main-source files constructing an IdentityService without wiring"
                        + " tesseraql.identity.maxRows. Its reads run unbounded. This ledger is"
                        + " shrink-only: pass the bound where a config is in reach, or add the"
                        + " site here in review with the reason none is")
                .containsExactlyInAnyOrderElementsOf(UNBOUNDED_CONSTRUCTIONS);
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
