package io.tesseraql.core.diag;

/**
 * A virtual-thread carrier pinning sample (design ch. 24): a virtual thread blocked while pinned to
 * its carrier (for example inside {@code synchronized} or a native frame), which defeats scaling.
 *
 * @param carrierThread  the carrier thread that was pinned
 * @param durationMs     how long the carrier was pinned, milliseconds
 * @param topFrame       the top stack frame where the pinning occurred, or null
 * @param atEpochMs      when the pinning was observed, epoch milliseconds
 */
public record PinningEvent(String carrierThread, long durationMs, String topFrame, long atEpochMs) {
}
