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
 * @param channel the configured channel name (required), e.g. {@code user-mail}
 * @param when    optional guard in the core expression language; a falsy guard skips the
 *                notification
 * @param payload map of payload key to source expression, resolved against the execution
 *                context; the payload rides the outbox event and feeds the channel's template
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotifySpec(String channel, String when, Map<String, String> payload) {

    public NotifySpec {
        payload = payload == null
                ? Map.of()
                : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
    }
}
