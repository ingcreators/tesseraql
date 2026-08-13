package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.spool.SpoolKind;
import io.tesseraql.core.spool.SpoolRef;
import io.tesseraql.core.spool.SpoolWriter;
import io.tesseraql.yaml.model.PipelineStep;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The {@code http:} step kind (roadmap Phase 26): one outbound REST call, read as rows. */
final class HttpStepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(HttpStepRunner.class);

    private HttpStepRunner() {
    }

    /**
     * Issues the step's outbound REST call (roadmap Phase 26) and publishes what every read
     * publishes: {@code rows} / {@code rowCount} / {@code first}, plus the call's own
     * {@code status} / {@code body} / {@code headers} (docs/unified-sources.md decision 10).
     * {@code select:} names the part of the response the rows come from, exactly as it does on
     * a route's {@code http:} source — the arm is one mechanism, so it answers one way wherever
     * it is declared. The call is synchronous and observable in the trace tree.
     *
     * <p>{@code mode: query-spool} streams those rows to a spool instead of holding them, so a
     * later {@code chunk:} step loads what an API returned (decision 19a). The gateway buffers
     * the response body either way — the spool bounds what the <em>rest of the job</em> holds,
     * not the call — so a genuinely large export still wants the API's own paging.
     *
     * <p>{@code onError: empty} degrades a failed call to zero rows and an {@code error} entry
     * rather than failing the step, the same declaration a route source makes; without it a
     * failure fails the step, and so the job.
     */
    static Map<String, Object> run(StepContext context) {
        PipelineStep step = context.step();
        if (context.httpCall() == null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "': an http: step needs the runtime's"
                            + " outbound HTTP client")
                    .build();
        }
        io.tesseraql.yaml.model.HttpSourceSpec source = step.sql().http();
        Map<String, Object> response;
        try {
            response = context.httpCall().call(source.call(), context.context(),
                    context.parentSpan());
        } catch (RuntimeException failure) {
            if (!source.degradesToEmpty()) {
                throw failure;
            }
            LOG.warn("Job step {} http: call failed; degrading to zero rows (onError: empty): {}",
                    step.id(), failure.getMessage());
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("rows", List.of());
            degraded.put("rowCount", 0);
            degraded.put("first", null);
            degraded.put("body", null);
            degraded.put("status", 0);
            degraded.put("error", failure.getMessage());
            return degraded;
        }
        Object body = io.tesseraql.yaml.http.HttpRows.select(response.get("body"),
                source.select());
        List<Map<String, Object>> rows = io.tesseraql.yaml.http.HttpRows.rows(body);
        Map<String, Object> result = new LinkedHashMap<>();
        if (source.spools()) {
            SpoolRef ref = spool(context, rows);
            result.put("rowCount", (int) ref.rows());
            result.put("spool", ref);
        } else {
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("first", rows.isEmpty() ? null : rows.get(0));
            result.put("body", body);
        }
        result.put("status", response.get("status"));
        result.put("headers", response.get("headers"));
        return result;
    }

    /**
     * The JSONL spool an {@code http:} acquisition fills from rows already in hand — the one
     * JSONL write loop left, because the response was JSON and JSON round-trips it faithfully;
     * a SQL extract spools tagged binary ({@link SqlStepRunner}) for the same fidelity reason.
     * A spool reference is the only thing a chunk reader takes, which is what lets one reader
     * load a SQL extract and an API result without knowing the difference
     * (docs/unified-sources.md decision 19a).
     */
    private static SpoolRef spool(StepContext context, List<Map<String, Object>> rows) {
        SpoolWriter writer = context.tempStore().createWriter(SpoolKind.JSONL);
        try (writer) {
            for (Map<String, Object> row : rows) {
                writer.write((context.mapper().writeValueAsString(row) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
                writer.incrementRows(1);
            }
        } catch (IOException ex) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + context.step().id() + "': spooling the response failed: "
                            + ex.getMessage())
                    .cause(ex)
                    .build();
        }
        // toRef() is only valid after close, which the try-with-resources performed.
        return writer.toRef();
    }
}
