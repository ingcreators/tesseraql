package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.notify.HmacSignatures;
import io.tesseraql.core.webhook.WebhookReplayStore;
import io.tesseraql.yaml.webhook.WebhookVerifiers;
import java.time.Instant;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Verifies an inbound webhook before its SQL pipeline runs (roadmap Phase 26): it authenticates
 * the delivery by HMAC over {@code <timestamp>.<body>} (the scheme the Phase 20 outbound webhook
 * signs with), rejects a stale or future timestamp outside the configured tolerance, and rejects
 * a replay via the shared {@link WebhookReplayStore} — so a delivery is processed at most once on
 * any node sharing the database. A failure throws before a single row is written.
 *
 * <p>The replay key is the configured delivery-id header when present, else the signature itself
 * (unique per signed payload). An id is retained only until its timestamp's tolerance lapses,
 * after which the timestamp check alone would reject the delivery.
 */
public final class WebhookVerifyProcessor implements Processor {

    /** TQL-SEC-4012: the webhook signature is missing or does not verify (HTTP 401). */
    private static final TqlErrorCode INVALID_SIGNATURE = new TqlErrorCode(TqlDomain.SEC, 4012);
    /** TQL-SEC-4013: the webhook timestamp is outside the tolerance window (HTTP 401). */
    private static final TqlErrorCode STALE_TIMESTAMP = new TqlErrorCode(TqlDomain.SEC, 4013);
    /** TQL-SEC-4014: the webhook delivery was already seen — a replay (HTTP 409). */
    private static final TqlErrorCode REPLAYED = new TqlErrorCode(TqlDomain.SEC, 4014);
    /** TQL-CAMEL-3110: the webhook replay store is not configured (HTTP 500). */
    private static final TqlErrorCode NO_STORE = new TqlErrorCode(TqlDomain.CAMEL, 3110);

    private final String routeId;
    private final WebhookVerifiers.Verifier verifier;

    public WebhookVerifyProcessor(String routeId, WebhookVerifiers.Verifier verifier) {
        this.routeId = routeId;
        this.verifier = verifier;
    }

    @Override
    public void process(Exchange exchange) {
        // The raw request bytes are what the sender signed; read them before any binder parses the
        // body, and set them back so the downstream JSON parse sees the same bytes.
        byte[] body = exchange.getMessage().getBody(byte[].class);
        if (body == null) {
            body = new byte[0];
        }
        exchange.getMessage().setBody(body);

        String signature = exchange.getMessage().getHeader(verifier.signatureHeader(),
                String.class);
        String timestamp = exchange.getMessage().getHeader(verifier.timestampHeader(),
                String.class);
        if (signature == null || signature.isBlank() || timestamp == null || timestamp.isBlank()) {
            throw new TqlException(INVALID_SIGNATURE, "Webhook '" + routeId
                    + "' is missing its signature or timestamp header");
        }
        if (!HmacSignatures.verify(verifier.secret(), timestamp, body, signature)) {
            throw new TqlException(INVALID_SIGNATURE,
                    "Webhook '" + routeId + "' signature did not verify");
        }

        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException ex) {
            throw new TqlException(STALE_TIMESTAMP,
                    "Webhook '" + routeId + "' timestamp is not epoch seconds");
        }
        long toleranceSeconds = verifier.tolerance().getSeconds();
        if (Math.abs(Instant.now().getEpochSecond() - epochSeconds) > toleranceSeconds) {
            throw new TqlException(STALE_TIMESTAMP, "Webhook '" + routeId
                    + "' timestamp is outside the " + verifier.tolerance() + " tolerance");
        }

        WebhookReplayStore store = exchange.getContext().getRegistry().lookupByNameAndType(
                TesseraqlProperties.WEBHOOK_REPLAY_STORE_BEAN, WebhookReplayStore.class);
        if (store == null) {
            throw new TqlException(NO_STORE, "Webhook replay store is not configured");
        }
        String deliveryId = verifier.idHeader()
                .map(header -> exchange.getMessage().getHeader(header, String.class))
                .filter(value -> value != null && !value.isBlank())
                .orElse(signature);
        Instant expiresAt = Instant.ofEpochSecond(epochSeconds).plus(verifier.tolerance());
        if (!store.markSeen(deliveryId, expiresAt)) {
            throw new TqlException(REPLAYED,
                    "Webhook '" + routeId + "' delivery was already processed (replay)");
        }
    }
}
