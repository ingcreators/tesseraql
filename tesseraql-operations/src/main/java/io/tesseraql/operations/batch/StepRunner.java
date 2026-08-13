package io.tesseraql.operations.batch;

import java.util.Map;

/**
 * One kind of pipeline step, run against the {@link StepContext} {@link JobExecutor} built for
 * it. The returned map is the step's published result — {@code affectedRows} for a write,
 * {@code rows} / {@code rowCount} / {@code first} for a read (docs/unified-sources.md decision
 * 10) — which later steps bind as {@code steps.<id>.*}.
 *
 * <p>Which runner a step gets is {@code JobExecutor.runnerFor}'s answer, and that dispatch stays
 * the one place the kinds are ordered. The app linter refuses most block combinations at build
 * time ("a step is one executable unit"), so the order is a formality on the authoring surface —
 * it is kept because the runtime must still pick exactly one.
 */
@FunctionalInterface
interface StepRunner {

    /** Runs the step this context names and publishes its result. */
    Map<String, Object> run(StepContext step);
}
