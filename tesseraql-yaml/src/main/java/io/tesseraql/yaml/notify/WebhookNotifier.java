package io.tesseraql.yaml.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.outbox.OutboxEvent;
import io.tesseraql.yaml.http.OutboundGateway;
import io.tesseraql.yaml.model.HttpCallSpec;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Delivers a notification to an HMAC-signed webhook channel (roadmap Phase 20).
 *
 * <p>The delivery goes out through the one {@link OutboundGateway} every other outbound call
 * uses (docs/lookups.md, decision 20). It used to build its own {@code HttpClient}, which made
 * the single path where the framework itself calls out the single path with no allow-list, no
 * named credential, a hard-coded connect timeout and no share in the per-host circuit breaker —
 * a hole in the deny-by-default posture the documentation states.
 *
 * <p>What stays here is the part that cannot move: the body and the signature. The HMAC covers
 * the exact bytes on the wire, so the bytes signed must be the bytes sent; a second
 * serialization inside the gateway could differ and every receiver would reject the signature.
 * The gateway contributes the policy, not the payload.
 */
public final class WebhookNotifier {

    /** TQL-BATCH-5303: a webhook delivery was not accepted by the receiver. */
    private static final TqlErrorCode DELIVERY_FAILED = new TqlErrorCode(TqlDomain.BATCH, 5303);

    private final ObjectMapper mapper = io.tesseraql.yaml.JsonMappers.constrained();
    private final OutboundGateway gateway;

    public WebhookNotifier(OutboundGateway gateway) {
        this.gateway = gateway;
    }

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

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put(HmacSignatures.TIMESTAMP_HEADER, timestamp);
        // The signature covers timestamp.body, so receivers authenticate and bound replay.
        channel.setting("secret").ifPresent(secret -> headers.put(
                HmacSignatures.SIGNATURE_HEADER, HmacSignatures.sign(secret, timestamp, bytes)));

        // A channel may name a credential and its own timeouts like any other call; unset, the
        // app-wide tesseraql.http.outbound defaults apply instead of a hard-coded pair.
        HttpCallSpec call = new HttpCallSpec("POST", url, Map.of(), Map.of(),
                channel.setting("credential").orElse(null), null, null,
                channel.setting("connectTimeout").orElse(null),
                channel.setting("requestTimeout").orElse(null));

        Map<String, Object> result;
        try {
            result = gateway.call(call, bytes, headers);
        } catch (TqlException ex) {
            // The gateway refuses a non-2xx in the vocabulary of an http-call. A delivery keeps
            // its own code: it is what the operations surface, the dead-letter rows and the
            // documentation name, and "http-call" would not tell an operator which webhook.
            throw new TqlException(DELIVERY_FAILED, "Webhook channel '" + channel.name()
                    + "' was not delivered: " + ex.getMessage(), ex);
        }
        Object status = result.get("status");
        int code = status instanceof Number number ? number.intValue() : 0;
        if (code / 100 != 2) {
            throw new TqlException(DELIVERY_FAILED, "Webhook channel '" + channel.name()
                    + "' answered HTTP " + code);
        }
    }
}
