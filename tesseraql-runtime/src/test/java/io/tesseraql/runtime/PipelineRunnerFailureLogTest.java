package io.tesseraql.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tesseraql.compiler.pipeline.Pipeline;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Step;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * A route failure has to leave a trace somewhere. The error envelope carries the status phrase
 * rather than the cause ({@code ErrorResponseRenderer} publishes {@code statusMessage}), and for a
 * failure whose library does not log itself — a SQL error reaches the runner wrapped in the
 * framework's own type, so no driver or template engine is left to report it — the runner is the
 * last place that holds the throwable.
 *
 * <p>Before this, it dropped it: the catch stored the exception as a property, handed it to the
 * renderer, and logged nothing. A 500 left an operator with a status phrase, a span saying
 * {@code error=true}, and no way to learn which statement failed short of attaching OTLP.
 *
 * <p>The backend here is {@code slf4j-simple}, which writes to {@code System.err} and resolves it
 * per line, so capturing the stream is enough. Its default threshold is info, which is also why
 * the client-fault case below is asserted as absent rather than present.
 */
class PipelineRunnerFailureLogTest {

    private static final TqlErrorCode SERVER_FAULT = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.SQL, 2500);
    // FIELD maps to 400 for everything but 4220/4222 — a malformed request.
    private static final TqlErrorCode CLIENT_FAULT = new TqlErrorCode(
            io.tesseraql.core.error.TqlDomain.FIELD, 4001);

    @Test
    void aServerFaultIsLoggedWithItsCauseAndTheRouteThatFailed() {
        String err = runFailing("orders.export",
                () -> new TqlException(SERVER_FAULT, "relation \"orders\" does not exist"));

        assertThat(err)
                .contains("orders.export")
                .contains("TQL-SQL-2500")
                .contains("relation \"orders\" does not exist")
                // The stack, not only the message: the point is to be able to find the throw site.
                .contains("io.tesseraql.core.error.TqlException");
    }

    /**
     * A malformed request is the caller's fault, not an operator's. Logging every 400 at error
     * level turns a scripted client into an alert storm and buries the failures that matter.
     */
    @Test
    void aClientFaultIsNotLoggedAtTheOperatorsLevel() {
        String err = runFailing("orders.create",
                () -> new TqlException(CLIENT_FAULT, "field 'qty' is not a number"));

        assertThat(err).doesNotContain("field 'qty' is not a number");
    }

    /** Runs a one-step pipeline whose step throws, and returns everything written to stderr. */
    private static String runFailing(String routeId, Supplier<RuntimeException> failure) {
        Step throwing = exchange -> {
            throw failure.get();
        };
        Step renderer = exchange -> {
            // Stands in for ErrorResponseRenderer: it answers, and it logs nothing.
        };
        Pipeline pipeline = new Pipeline(routeId, List.of(throwing),
                List.of(new Pipeline.Handler(List.of(Throwable.class), renderer)), -1, null);

        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            PipelineRunner.run(pipeline, new Exchange(io.tesseraql.pipeline.Beans.NONE), false);
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
