package io.tesseraql.operations.batch;

import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.expr.EvaluationContext;
import io.tesseraql.yaml.model.PipelineStep;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code push:} step kind: delivers a produced file to a declared target. */
final class PushStepRunner {

    private PushStepRunner() {
    }

    /**
     * Runs a push step (docs/analytics-experience.md): the {@code file:} path resolves to a
     * transfer id — typically an earlier export step's {@code steps.<id>.transferId} — whose
     * produced file the wired pusher delivers to the target. Reading the transfer counts as
     * its first download (a route-produced transfer's {@code download}-timed follow-up fires),
     * because delivered is downloaded.
     */
    static Map<String, Object> run(StepContext context) {
        PipelineStep step = context.step();
        if (context.filePusher() == null || context.fileTransfers() == null) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "' declares push: but no push/transfer"
                            + " service is wired")
                    .build();
        }
        io.tesseraql.yaml.model.PushSpec push = step.push();
        Object resolved = new EvaluationContext(context.context())
                .resolve(Arrays.asList(push.file().split("\\.")));
        String transferId = resolved == null ? null : String.valueOf(resolved);
        if (transferId == null || transferId.isBlank()) {
            throw TqlException.builder(StepContext.STEP_ERROR)
                    .message("Step '" + step.id() + "': push file: '" + push.file()
                            + "' resolved to no transfer id")
                    .build();
        }
        io.tesseraql.core.files.FileTransferService.Download download = context.fileTransfers()
                .download(transferId)
                .orElseThrow(() -> TqlException.builder(StepContext.STEP_ERROR)
                        .message("Step '" + step.id() + "': transfer " + transferId
                                + " has no downloadable file")
                        .build());
        String filename = push.as() == null || push.as().isBlank()
                ? download.filename()
                : context.interpolate(push.as());
        context.filePusher().push(push, filename, download.content());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("affectedRows", 1);
        result.put("transferId", transferId);
        result.put("filename", filename);
        result.put("target", push.effectiveTransport());
        return result;
    }
}
