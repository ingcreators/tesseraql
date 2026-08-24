package io.tesseraql.docs;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.binding.ErrorResponseRenderer;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The code-to-status ledger (docs/duplication-consolidation.md, campaign 3, structural
 * decision 7): every registered {@code 4xxx} code either answers a real status through
 * {@code ErrorResponseRenderer.httpStatus} or is recorded here as one that correctly answers
 * 500 — because it never rides the renderer (a lint finding, a boot refusal, a job-time or
 * CLI failure) or because 500 is its honest meaning (a server-side fault wearing a refusal
 * number).
 *
 * <p>The defect class this closes shipped twice before it had a guard: a refusal added
 * without a mapping reads as "Internal Server Error" — the access-governance campaign found
 * it in slice 2 and again in slice 7, in the very next number (the switch's own comments
 * record both). The first run of this audit found sixteen more: eleven Studio input
 * rejections, the doc portal's not-found, the invitation surface, the body limit, a
 * malformed decision scaffold, and the MCP transport's pair. From here, <b>a new
 * {@code 4xxx} code fails the build until its author answers the status question the moment
 * they mint it</b> — by mapping it in the switch, or by recording it here in review with its
 * reason. Domains that exist only as lint string constants (ADM) never construct a
 * {@code TqlErrorCode} and are outside the renderer's reach by type; the scan skips them.
 *
 * <p>A recorded code that later gains a mapping must leave this list (the second assertion),
 * so the list can only shrink toward codes whose 500 is a decision, never drift.
 */
class StatusMappingLedgerTest {

    private static final Path REPO = Path.of("..");

    /** Codes numbered {@code 4xxx} whose 500 is the recorded decision. */
    private static final Set<String> RECORDED = new TreeSet<>(List.of(
            // Lint findings and boot refusals: raised at author/boot time, never through the
            // renderer (calendar/chunk/chain/overlap/heartbeat rules; the reaped-execution
            // record lives on the execution row).
            "TQL-BATCH-4201", "TQL-BATCH-4202", "TQL-BATCH-4203", "TQL-BATCH-4204",
            "TQL-BATCH-4205", "TQL-BATCH-4206", "TQL-BATCH-4207", "TQL-BATCH-4208",
            "TQL-BATCH-4209", "TQL-BATCH-4210", "TQL-BATCH-4211", "TQL-BATCH-4212",
            // The runtime decision range keeps its documented 500 (the switch's own comment):
            // a multi-hit, a miss with no default, and a failed generated lookup are data or
            // declaration faults, not the caller's request.
            "TQL-DECISION-4720", "TQL-DECISION-4721", "TQL-DECISION-4722", "TQL-DECISION-4723",
            // MCP: 4002 is a JSON-RPC argument error answered inside the protocol; 4030/4261
            // are lint rules; 4262 is a boot refusal.
            "TQL-MCP-4002", "TQL-MCP-4030", "TQL-MCP-4261", "TQL-MCP-4262",
            // SEC: the security namespace's lint rules (auth/key/mTLS/egress/messaging/
            // header declarations), boot refusals (copilot gate, SAML https pin, token
            // issuing), the documented federation 500 (4140), the job-time push-egress
            // denial (4141), and the two auth misconfigurations the switch's own comment
            // argues must stay 500 (4000/4001: a 401 invites token-refresh retries against
            // a genuinely broken server).
            "TQL-SEC-4000", "TQL-SEC-4001", "TQL-SEC-4030", "TQL-SEC-4040", "TQL-SEC-4041",
            "TQL-SEC-4042", "TQL-SEC-4043", "TQL-SEC-4044", "TQL-SEC-4045", "TQL-SEC-4046",
            "TQL-SEC-4047", "TQL-SEC-4048", "TQL-SEC-4049", "TQL-SEC-4050", "TQL-SEC-4051",
            "TQL-SEC-4052", "TQL-SEC-4053", "TQL-SEC-4060", "TQL-SEC-4061", "TQL-SEC-4062",
            "TQL-SEC-4063", "TQL-SEC-4064", "TQL-SEC-4065", "TQL-SEC-4066", "TQL-SEC-4070",
            "TQL-SEC-4071", "TQL-SEC-4072", "TQL-SEC-4080", "TQL-SEC-4081", "TQL-SEC-4082",
            "TQL-SEC-4083", "TQL-SEC-4084", "TQL-SEC-4085", "TQL-SEC-4087", "TQL-SEC-4088",
            "TQL-SEC-4089", "TQL-SEC-4090", "TQL-SEC-4091", "TQL-SEC-4092", "TQL-SEC-4093",
            // 4121: the boot-time invite/recovery channel refusals — split out of 4120,
            // which the renderer answers 404 for the invitation surface being absent.
            "TQL-SEC-4100", "TQL-SEC-4110", "TQL-SEC-4121",
            "TQL-SEC-4130", "TQL-SEC-4131", "TQL-SEC-4132",
            "TQL-SEC-4133", "TQL-SEC-4134", "TQL-SEC-4135", "TQL-SEC-4136", "TQL-SEC-4137",
            "TQL-SEC-4139", "TQL-SEC-4140", "TQL-SEC-4141", "TQL-SEC-4145", "TQL-SEC-4146",
            // Studio server-side failures: an unreadable doc or catalog, and the copilot's
            // unconfigured-or-failed model endpoint — not the caller's input.
            "TQL-STUDIO-4041", "TQL-STUDIO-4235", "TQL-STUDIO-4242",
            // Raised by the CLI against a local directory; never rides the renderer.
            "TQL-UPGRADE-4092"));

    @Test
    void everyRefusalNumberedCodeAnswersItsStatusOrIsRecorded() throws IOException {
        Set<String> violations = new TreeSet<>();
        Set<String> seen = new TreeSet<>();
        for (Map.Entry<String, Map<Integer, ErrorIndex.Code>> domain : ErrorIndex.scan(REPO)
                .entrySet()) {
            TqlDomain parsed;
            try {
                parsed = TqlDomain.valueOf(domain.getKey());
            } catch (IllegalArgumentException notADomain) {
                continue;
            }
            for (Integer number : domain.getValue().keySet()) {
                if (number < 4000 || number > 4999) {
                    continue;
                }
                TqlErrorCode code = new TqlErrorCode(parsed, number);
                seen.add(code.toString());
                boolean answers500 = ErrorResponseRenderer.httpStatus(code) == 500;
                if (answers500 && !RECORDED.contains(code.toString())) {
                    violations.add(code.toString());
                }
                if (!answers500 && RECORDED.contains(code.toString())) {
                    violations.add("(recorded but mapped) " + code);
                }
            }
        }
        for (String recorded : RECORDED) {
            if (!seen.contains(recorded)) {
                violations.add("(recorded but retired) " + recorded);
            }
        }
        assertThat(violations)
                .as("every 4xxx code answers a status through ErrorResponseRenderer"
                        + ".httpStatus or is RECORDED here with its reason; '(recorded but"
                        + " mapped)' means the mapping landed and '(recorded but retired)'"
                        + " means the code is gone — remove either from the list")
                .isEmpty();
    }
}
