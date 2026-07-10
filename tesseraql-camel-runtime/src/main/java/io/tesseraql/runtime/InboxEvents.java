package io.tesseraql.runtime;

import io.tesseraql.compiler.binding.InboxBadge;
import io.tesseraql.core.inbox.InboxStore;
import java.time.Duration;
import org.apache.camel.CamelContext;

/**
 * The framework's per-session event stream, {@code GET /_tesseraql/events} (docs/inbox.md,
 * "Live badge"): the kit's sse-updates recipe over an {@link SseRoutes} endpoint. Today it
 * carries one named event — {@code inbox:badge}, the shell bell's badge fragment, pushed
 * whenever the signed-in subject's unread count changes ({@link NotifyingInboxStore}) —
 * and idle {@code ping} frames double as heartbeats. Each stream bounds its own lifetime;
 * the browser's EventSource reconnects at the server-set {@code retry} delay, which also
 * covers a stream evicted by the {@link InboxStreams} caps. Registered exactly when an
 * inbox channel is configured, like the bell itself.
 */
final class InboxEvents {

    private static final Duration HEARTBEAT = Duration.ofSeconds(25);
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final long RETRY_MILLIS = 2_000;

    private InboxEvents() {
    }

    static void register(CamelContext context, int port, InboxStreams streams,
            InboxStore inbox) {
        SseRoutes.register(context, port, "/_tesseraql/events", (principal, query) -> {
            String tenant = principal.tenantId();
            String subject = principal.subject();
            return writer -> {
                long deadline = System.currentTimeMillis() + LIFETIME.toMillis();
                // Subscribe before the first frame: once the client sees the stream open,
                // a delivery can no longer slip between connect and subscribe.
                try (InboxStreams.Subscription events = streams.subscribe(tenant, subject)) {
                    writer.retry(RETRY_MILLIS);
                    while (System.currentTimeMillis() < deadline) {
                        switch (events.await(HEARTBEAT)) {
                            case CHANGED -> writer.event("inbox:badge",
                                    InboxBadge.html(inbox.unreadCount(tenant, subject)));
                            case IDLE -> writer.event("ping", "");
                            case CLOSED -> {
                                return;
                            }
                        }
                    }
                }
            };
        });
    }
}
