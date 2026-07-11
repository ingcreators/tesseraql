package io.tesseraql.runtime;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.camel.CamelContext;

/**
 * The app-author live-view stream, {@code GET /_tesseraql/topics?topics=a,b}
 * (docs/realtime.md): the same {@link SseRoutes} transport as the inbox badge, mounted
 * exactly when a route declares {@code emit:}. Each named event is a topic the page listens
 * to; the payload is always empty — a subscriber refetches through its own authorized route,
 * so the stream never carries data. Requested topics are filtered to the app-declared set,
 * subscriptions are tenant-scoped, and idle {@code ping} frames double as heartbeats.
 */
final class TopicEvents {

    private static final Duration HEARTBEAT = Duration.ofSeconds(25);
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final long RETRY_MILLIS = 2_000;

    private TopicEvents() {
    }

    static void register(CamelContext context, int port, TopicStreams streams,
            Set<String> declaredTopics) {
        SseRoutes.register(context, port, "/_tesseraql/topics", (principal, query) -> {
            String tenant = principal.tenantId();
            String subject = principal.subject();
            List<String> topics = requestedTopics(query.apply("topics"), declaredTopics);
            return writer -> {
                long deadline = System.currentTimeMillis() + LIFETIME.toMillis();
                // Subscribe before the first frame: once the client sees the stream open, an
                // emit can no longer slip between connect and subscribe.
                try (TopicStreams.Subscription events = streams.subscribe(tenant, subject,
                        topics)) {
                    writer.retry(RETRY_MILLIS);
                    while (System.currentTimeMillis() < deadline) {
                        String fired = events.await(HEARTBEAT);
                        if (fired.equals(TopicStreams.CLOSED)) {
                            return;
                        }
                        if (fired.equals(TopicStreams.IDLE)) {
                            writer.event("ping", "");
                        } else {
                            writer.event(fired, "");
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
