package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.InboxBadge;
import io.tesseraql.core.inbox.InboxStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.camel.CamelContext;

/**
 * The framework's per-session event stream, {@code GET /_tesseraql/events} (docs/inbox.md
 * "Live badge", docs/realtime.md): the kit's sse-updates recipe over an {@link SseRoutes}
 * endpoint. Named events are the wire contract — {@code inbox:badge} carries the shell bell's
 * badge fragment whenever the signed-in subject's unread count changes, and each live-view
 * topic the page asks for via {@code ?topics=} is one named, always-empty event (a subscriber
 * refetches through its own authorized route, so the stream never carries data). Requested
 * topics filter to the app-declared set; idle {@code ping} frames double as heartbeats; each
 * stream bounds its own lifetime and the browser's EventSource reconnects at the server-set
 * {@code retry} delay, which also covers a stream evicted by the {@link LiveStreams} caps.
 * Registered when an inbox channel is configured or any route declares {@code emit:}.
 */
final class LiveEvents {

    private static final Duration HEARTBEAT = Duration.ofSeconds(25);
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final long RETRY_MILLIS = 2_000;

    private LiveEvents() {
    }

    /** {@code inbox} is null when no inbox channel is configured (topics-only apps). */
    static void register(CamelContext context, int port, LiveStreams streams, InboxStore inbox,
            Set<String> declaredTopics) {
        SseRoutes.register(context, port, "/_tesseraql/events", (principal, query) -> {
            String tenant = principal.tenantId();
            String subject = principal.subject();
            // Fired signal key → the named event it becomes on the wire.
            Map<String, String> events = new LinkedHashMap<>();
            if (inbox != null) {
                events.put(LiveStreams.inboxKey(tenant, subject), "inbox:badge");
            }
            for (String topic : requestedTopics(query.apply("topics"), declaredTopics)) {
                events.put(LiveStreams.topicKey(tenant, topic), topic);
            }
            return writer -> {
                long deadline = System.currentTimeMillis() + LIFETIME.toMillis();
                // Subscribe before the first frame: once the client sees the stream open, a
                // delivery or an emit can no longer slip between connect and subscribe.
                try (LiveStreams.Subscription signals = streams.subscribe(subject,
                        new ArrayList<>(events.keySet()))) {
                    writer.retry(RETRY_MILLIS);
                    while (System.currentTimeMillis() < deadline) {
                        String fired = signals.await(HEARTBEAT);
                        if (fired.equals(LiveStreams.CLOSED)) {
                            return;
                        }
                        if (fired.equals(LiveStreams.IDLE)) {
                            writer.event("ping", "");
                        } else if ("inbox:badge".equals(events.get(fired))) {
                            writer.event("inbox:badge",
                                    InboxBadge.html(inbox.unreadCount(tenant, subject)));
                        } else {
                            writer.event(events.get(fired), "");
                        }
                    }
                }
            };
        });
    }

    /** The requested topics, filtered to the declared set (an unknown topic never fires). */
    private static List<String> requestedTopics(String raw, Set<String> declared) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(declared::contains)
                .distinct()
                .toList();
    }
}
