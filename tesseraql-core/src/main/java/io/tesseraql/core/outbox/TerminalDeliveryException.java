package io.tesseraql.core.outbox;

/**
 * A delivery failure that retrying can never fix — a configuration refusal, not an outage. The
 * dispatcher dead-letters the event at once instead of burning the whole attempt budget on
 * identical refusals; the event stays visible to operators, who fix the configuration and
 * redeliver. A sink throws this only when the failure is deterministic — anything that might
 * heal (a transport fault, an open circuit, a provider outage) stays a plain exception and
 * keeps its at-least-once retries.
 */
public final class TerminalDeliveryException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TerminalDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
