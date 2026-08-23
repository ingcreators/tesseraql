package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The HTTP-client ledger (docs/duplication-consolidation.md, campaign 1, the
 * {@code SqlExecutorLedgerTest} pattern): every main source file that constructs an HTTP client
 * is named here. The campaign found fourteen sites bypassing the one outbound seam — unbounded,
 * unclassified, unobserved calls, one of them with no timeout at all — and retired them onto
 * {@code OutboundGateway}/{@code HttpCallClient} (egress) or {@code LoopbackCall} (intra-stack);
 * this ledger keeps them retired.
 *
 * <p><b>A new entry is refused by default.</b> An outbound call leaves through the gateway; an
 * intra-stack hop rides {@code LoopbackCall}; a build that adds a client construction anywhere
 * else must say so here, in review, with a reason. Removing an entry just shrinks the list.
 *
 * <p>What stays, and why:
 *
 * <ul>
 * <li>{@code HttpCallClient} — the egress primitive itself.</li>
 * <li>{@code LoopbackCall} — the intra-stack primitive itself.</li>
 * <li>{@code MultiAppGateway} — the Vert.x streaming reverse proxy, consolidated on its own
 * terms (pool sizing, read-idle, relay codes); folding it into a request/response primitive
 * would be shape for shape's sake.</li>
 * <li>{@code CopilotService} — a streaming SSE read the gateway's raw form cannot carry;
 * dev-only, bounded, boot-gated against the same allow-list ({@code TQL-SEC-4085}).</li>
 * <li>The CLI trio ({@code TokenCommand}, {@code DeployCommand}, {@code UpdateNotifier}) — a
 * developer's own machine, no egress policy and no tracer; structural decision 3 gives them
 * timeouts, not the primitive.</li>
 * <li>The test-core pair ({@code RouteTestRunner}, {@code HttpCallCases}) — main-source files
 * that exist only to drive tests, the SQL ledger's precedent.</li>
 * </ul>
 */
class HttpClientLedgerTest {

    private static final Path REPO = Path.of("..");

    private static final Set<String> LEDGER = new TreeSet<>(List.of(
            "tesseraql-cli/src/main/java/io/tesseraql/cli/DeployCommand.java",
            "tesseraql-cli/src/main/java/io/tesseraql/cli/TokenCommand.java",
            "tesseraql-cli/src/main/java/io/tesseraql/cli/UpdateNotifier.java",
            "tesseraql-operations/src/main/java/io/tesseraql/operations/http/HttpCallClient.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/LoopbackCall.java",
            "tesseraql-runtime/src/main/java/io/tesseraql/runtime/MultiAppGateway.java",
            "tesseraql-studio/src/main/java/io/tesseraql/studio/CopilotService.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/HttpCallCases.java",
            "tesseraql-test-core/src/main/java/io/tesseraql/test/RouteTestRunner.java"));

    @Test
    void everyClientConstructionSiteIsOnTheLedger() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(REPO)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().contains("/src/main/java/"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            if (source.contains("HttpClient.newHttpClient(")
                                    || source.contains("HttpClient.newBuilder(")
                                    || source.contains("createHttpClient(")) {
                                found.add(REPO.relativize(path).toString().replace('\\', '/'));
                            }
                        } catch (IOException unreadable) {
                            throw new java.io.UncheckedIOException(unreadable);
                        }
                    });
        }
        assertThat(found)
                .as("main-source files constructing an HTTP client — a NEW entry means a"
                        + " second HTTP stack: route egress through OutboundGateway and"
                        + " intra-stack hops through LoopbackCall instead, or add it here in"
                        + " review with a reason; a REMOVED entry just shrinks this list")
                .containsExactlyInAnyOrderElementsOf(LEDGER);
    }
}
