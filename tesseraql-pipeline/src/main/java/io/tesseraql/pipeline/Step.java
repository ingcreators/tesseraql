package io.tesseraql.pipeline;

/**
 * One step of a compiled pipeline (docs/camel-removal.md structural decision 2).
 *
 * <p>This was {@code org.apache.camel.Processor}, and the shape is deliberately identical — one
 * method, taking the exchange, allowed to throw. Keeping the shape is what let 116 files change
 * type without changing behaviour: a rewrite of the method signature would have made every
 * implementation a place for a difference to hide.
 */
@FunctionalInterface
public interface Step {

    /**
     * Runs this step against {@code exchange}.
     *
     * <p>Throwing is how a step refuses: the pipeline's error clauses answer for it, and the
     * completions registered on the exchange run either way.
     */
    void process(Exchange exchange) throws Exception;
}
