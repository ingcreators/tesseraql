package io.tesseraql.yaml.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.outbox.OutboxEvent;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a notification to an outbound webhook (roadmap Phase 20): a JSON POST carrying the
 * envelope payload, signed with HMAC-SHA256 over {@code <timestamp>.<body>} when the channel
 * declares a {@code secret}. A non-2xx answer (or a transport failure) throws, so the outbox
 * dispatcher retries and eventually dead-letters the event.
 */
public final class WebhookNotifier {

    /** TQL-BATCH-5303: a webhook delivery was not accepted by the receiver. */
    private static final TqlErrorCode DELIVERY_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5303);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            // Honor the JVM proxy configuration; without it the JDK client ignores proxy props.
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event) throws Exception {
        send(channel, envelope, event, null);
    }

    /**
     * Delivery with the destination overridden — the declarative test runner's real-send mode
     * (docs/testing.md): the request, headers, and HMAC signature are built exactly as for the
     * channel's own url, but the wire goes to the runner's capture server.
     */
    public void send(NotificationChannels.Channel channel, NotifyEvents.Envelope envelope,
            OutboxEvent event, String urlOverride) throws Exception {
        String url = urlOverride != null ? urlOverride : channel.require("url");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", envelope.source());
        body.put("eventId", event.id());
        body.put("app", event.appName());
        body.put("payload", envelope.payload());
        byte[] bytes = mapper.writeValueAsBytes(body);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .header(HmacSignatures.TIMESTAMP_HEADER, timestamp)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes));
        // The signature covers timestamp.body, so receivers authenticate and bound replay.
        channel.setting("secret").ifPresent(secret -> request
                .header(HmacSignatures.SIGNATURE_HEADER,
                        HmacSignatures.sign(secret, timestamp, bytes)));

        HttpResponse<Void> response = client.send(request.build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() / 100 != 2) {
            throw new TqlException(DELIVERY_FAILED, "Webhook channel '" + channel.name()
                    + "' answered HTTP " + response.statusCode());
        }
    }
}
