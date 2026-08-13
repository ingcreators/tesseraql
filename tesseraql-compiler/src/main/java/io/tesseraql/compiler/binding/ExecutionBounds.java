package io.tesseraql.compiler.binding;

/**
 * The execution bounds a statement inherits, resolved by the compiler from the same config keys
 * the route-level SQL path uses. A command opens its own JDBC transaction and has no transaction
 * manager to bound it, so without these a step can hold a pool connection open indefinitely and
 * materialize an unbounded result set inside an open write transaction. The same currency bounds
 * an {@code enrich:} reference query, which runs on the same terms.
 *
 * @param timeoutSeconds statement timeout; {@code 0} disables it, as at route level
 * @param maxRows        row cap for {@code mode: query} statements; negative disables it
 * @param onOverflow     {@code fail} (default) or {@code warn} to truncate with a log line
 */
public record ExecutionBounds(int timeoutSeconds, int maxRows, String onOverflow) {
}
