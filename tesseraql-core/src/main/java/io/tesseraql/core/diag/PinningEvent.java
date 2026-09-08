package io.tesseraql.core.diag;

/**
 * A virtual-thread carrier pinning sample (design ch. 24): a virtual thread blocked while pinned to
 * its carrier (for example inside {@code synchronized} or a native frame), which defeats scaling.
 *
 * @param carrierThread  the carrier thread that was pinned, or "unknown" if the event did not
 *                       name one
 * @param durationMs     how long the carrier was pinned, milliseconds
 * @param topFrame       the innermost frame outside {@code java.*}, {@code javax.*},
 *                       {@code jdk.*} and {@code sun.*} — the application code the pinning came
 *                       from, not the VM's reporting frame. Falls back to the VM's stated reason
 *                       when the stack is platform code all the way down, and is null when the
 *                       event carries neither
 * @param atEpochMs      when the pinning happened, epoch milliseconds — the event's own start
 *                       time, not the moment JFR delivered it
 */
public record PinningEvent(String carrierThread, long durationMs, String topFrame, long atEpochMs) {
}
