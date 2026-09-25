package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.operations.app.StackSettings;
import io.tesseraql.yaml.config.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The front door's share of a member, and the member's own gate, default to one number that
 * nothing else derives (docs/capacity-defaults.md decisions 1 and 3).
 *
 * <p>The share was the stack's worker count, ten, from when a route ran on the worker pool; the
 * member's gate was four times its own. Under a stack every request crosses the front door first,
 * so the member's forty were never reached, and a closed-loop load test at 32 workers was refused
 * 65% of the time at the door (docs/gateway-performance.md row 2).
 */
class FrontDoorShareTest {

    @TempDir
    Path stack;

    @Test
    void theShareDefaultsToWhatAMembersGateAdmits() throws Exception {
        assertThat(MultiAppGateway.maxConcurrentPerMember(null)).isEqualTo(40);
        assertThat(MultiAppGateway.maxConcurrentPerMember(stackFile("")))
                .isEqualTo(TesseraqlRuntime.DEFAULT_MAX_IN_FLIGHT);
    }

    /** The regression pin: a stack's worker count no longer decides how much its door forwards. */
    @Test
    void theStacksWorkerCountNoLongerFeedsTheShare() throws Exception {
        StackSettings settings = stackFile("""
                  http:
                    workerThreads: 20
                """);

        assertThat(MultiAppGateway.maxConcurrentPerMember(settings)).isEqualTo(40);
    }

    @Test
    void aDeclaredShareWins() throws Exception {
        StackSettings settings = stackFile("""
                  gateway:
                    maxConcurrentPerMember: 7
                """);

        assertThat(MultiAppGateway.maxConcurrentPerMember(settings)).isEqualTo(7);
        assertThat(MultiAppGateway.maxStreamsPerMember(settings, 7))
                .as("the stream share follows the request share, as a member's"
                        + " maxEventStreams follows its maxInFlight")
                .isEqualTo(7);
    }

    @Test
    void theStreamShareDefaultsToTheRequestShareAndADeclaredOneWins() throws Exception {
        assertThat(MultiAppGateway.maxStreamsPerMember(null, 40)).isEqualTo(40);
        StackSettings settings = stackFile("""
                  gateway:
                    maxStreamsPerMember: 12
                """);

        assertThat(MultiAppGateway.maxStreamsPerMember(settings, 40)).isEqualTo(12);
    }

    /** The member's side of the same number: forty whatever its worker count says. */
    @Test
    void aMembersGateDefaultsToFortyWhateverItsWorkerCount() {
        AppConfig workers = config(Map.of("workerThreads", 20));

        assertThat(TesseraqlRuntime.maxInFlight(workers)).isEqualTo(40);
        assertThat(TesseraqlRuntime.maxEventStreams(workers)).isEqualTo(40);
    }

    @Test
    void aMembersDeclaredBoundsWin() {
        AppConfig declared = config(Map.of("maxInFlight", 12));

        assertThat(TesseraqlRuntime.maxInFlight(declared)).isEqualTo(12);
        assertThat(TesseraqlRuntime.maxEventStreams(declared))
                .as("streams follow the declared in-flight bound").isEqualTo(12);
        assertThat(TesseraqlRuntime.maxEventStreams(
                config(Map.of("maxInFlight", 12, "maxEventStreams", 3)))).isEqualTo(3);
    }

    /**
     * A member whose own gate admits more than its share is named at start, with the stack key
     * that closes the gap — neither followed silently nor left to show as the door's 503.
     */
    @Test
    void aMemberThatAdmitsMoreThanItsShareIsNamed() {
        List<String> warnings = MultiAppGateway.membersAboveTheShare(List.of(
                new MultiAppGateway.MemberBounds("orders", 80, 40),
                new MultiAppGateway.MemberBounds("ledger", 40, 40),
                new MultiAppGateway.MemberBounds("live", 40, 100)), 40, 40);

        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).startsWith("orders admits 80 requests in flight")
                .contains("tesseraql.gateway.maxConcurrentPerMember")
                .contains(StackSettings.FILE_NAME);
        assertThat(warnings.get(1)).startsWith("live admits 100 event streams")
                .contains("tesseraql.gateway.maxStreamsPerMember");
    }

    @Test
    void aMemberWithinItsShareIsNotNamed() {
        assertThat(MultiAppGateway.membersAboveTheShare(List.of(
                new MultiAppGateway.MemberBounds("small", 4, 1)), 40, 40)).isEmpty();
    }

    private StackSettings stackFile(String tesseraqlBlock) throws Exception {
        Files.writeString(stack.resolve(StackSettings.FILE_NAME),
                tesseraqlBlock.isEmpty()
                        ? "# nothing declared\n"
                        : "tesseraql:\n" + tesseraqlBlock);
        return StackSettings.load(stack);
    }

    private static AppConfig config(Map<String, Object> http) {
        return new AppConfig(Map.of("tesseraql", Map.of("http", http)));
    }
}
