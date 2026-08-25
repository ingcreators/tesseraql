package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * A notification declaration (roadmap Phase 20): one message sent through a configured
 * notification channel ({@code tesseraql.notifications.channels.<name>}), enqueued on the
 * transactional outbox so delivery is at-least-once with retries and dead-letters.
 *
 * <p>On a command route, the {@code notify:} block maps notification ids to specs; the events
 * are written in the command's transaction, after the steps. In a batch pipeline, a step
 * declares {@code notify:} instead of {@code sql:} and the event is enqueued when the step runs.
 *
 * @param channel   the configured channel name (required), e.g. {@code user-mail}
 * @param when      optional guard in the core expression language; a falsy guard skips the
 *                  notification
 * @param recipient optional expression naming the recipient's subject (roadmap Phase 48),
 *                  e.g. {@code principal.subject} or {@code body.assignee}; when present, the
 *                  enqueue path honors that subject's per-channel opt-out preference. A
 *                  notification without a recipient is channel-level and always delivered.
 * @param attach    optional expression resolving to a transfer id — an export step's
 *                  {@code steps.<id>.transferId} — whose produced file rides the mail as an
 *                  attachment (docs/analytics-experience.md; mail channels only, the bytes
 *                  are read from the transfer store at delivery time)
 * @param payload   map of payload key to source expression, resolved against the execution
 *                  context; the payload rides the outbox event and feeds the channel's template
 * @param delay     hold the entry back this long after the commit, e.g. {@code 72h}
 * @param deliverAt hold the entry back until this bindable instant; exclusive with
 *                  {@code delay:}
 * @param cancelKey the withdrawal key this entry is written under, so a later command can
 *                  withdraw it while it is still undelivered
 * @param cancel    a bindable path whose value names entries to withdraw — the withdrawing
 *                  form of the block, declared instead of {@code channel:}, never beside it
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifySpec(String channel, String when, String recipient, String attach,
        Map<String, String> payload, String delay, String deliverAt, String cancelKey,
        String cancel) {

    public NotifySpec {
        payload = payload == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }

    /** This entry's declared schedule, empty when it is deliverable at once. */
    public ScheduleSpec schedule() {
        return new ScheduleSpec(delay, deliverAt);
    }

    /**
     * Whether this entry withdraws rather than sends. An order cancelled on day two must not
     * remind on day three, and the withdrawal is a declaration of the command that cancels it
     * (docs/notifications.md, "Scheduled delivery").
     */
    public boolean withdraws() {
        return cancel != null && !cancel.isBlank();
    }

    /** The shorthand every pre-scheduling positional caller used. */
    public NotifySpec(String channel, String when, String recipient, String attach,
            Map<String, String> payload) {
        this(channel, when, recipient, attach, payload, null, null, null, null);
    }

    /** Recipient-less form (the shape before roadmap Phase 48) for positional callers. */
    public NotifySpec(String channel, String when, Map<String, String> payload) {
        this(channel, when, null, null, payload);
    }

    /** Attachment-less form (the shape before the export step) for positional callers. */
    public NotifySpec(String channel, String when, String recipient,
            Map<String, String> payload) {
        this(channel, when, recipient, null, payload);
    }
}
