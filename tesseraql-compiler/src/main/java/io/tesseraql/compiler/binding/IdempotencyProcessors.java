package io.tesseraql.compiler.binding;

import io.tesseraql.camel.TesseraqlProperties;
import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.idempotency.IdempotencyStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;

/**
 * Camel processors implementing request idempotency (design ch. 39.4, 39.5).
 *
 * <p>{@link Begin} runs before request binding: on a replay it sets the stored response and marks
 * the exchange so the route short-circuits; on a conflict it raises {@code TQL-IDEM-4090} (409).
 * {@link Complete} runs after the response is rendered and persists it for future replays.
 */
public final class IdempotencyProcessors {

    /** Exchange property set to {@code true} when the response is a stored replay. */
    public static final String REPLAY_PROPERTY = "TqlIdemReplay";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.IDEM, 4090);
    private static final TqlErrorCode KEY_REQUIRED = new TqlErrorCode(TqlDomain.FIELD, 2003);

    private IdempotencyProcessors() {
    }

    /** Begins idempotent processing before request binding. */
    public static Processor begin(String scope, long ttlMillis, boolean required) {
        return exchange -> {
            String key = exchange.getMessage().getHeader(KEY_HEADER, String.class);
            if (key == null || key.isBlank()) {
                if (required) {
                    throw new TqlException(KEY_REQUIRED,
                            "Missing required " + KEY_HEADER + " header");
                }
                return; // idempotency optional and not requested
            }
            IdempotencyStore store = store(exchange);
            String hash = requestHash(exchange);
            IdempotencyStore.BeginResult result = store.begin(scope, key, hash, ttlMillis);
            switch (result) {
                case IdempotencyStore.Proceed ignored -> {
                    // first time: proceed; Complete will persist the response
                }
                case IdempotencyStore.Replay replay -> {
                    exchange.setProperty(REPLAY_PROPERTY, true);
                    exchange.getMessage().setBody(replay.body());
                    exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, replay.status());
                    if (replay.contentType() != null) {
                        exchange.getMessage().setHeader(Exchange.CONTENT_TYPE,
                                replay.contentType());
                    }
                }
                case IdempotencyStore.Conflict conflict ->
                    throw new TqlException(CONFLICT, conflict.reason());
            }
        };
    }

    /** Persists the rendered response so future requests with the same key replay it. */
    public static Processor complete(String scope) {
        return exchange -> {
            if (Boolean.TRUE.equals(exchange.getProperty(REPLAY_PROPERTY))) {
                return;
            }
            String key = exchange.getMessage().getHeader(KEY_HEADER, String.class);
            if (key == null || key.isBlank()) {
                return;
            }
            int status = exchange.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 200,
                    Integer.class);
            String body = exchange.getMessage().getBody(String.class);
            String contentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE,
                    String.class);
            store(exchange).complete(scope, key, status, body, contentType);
        };
    }

    private static IdempotencyStore store(Exchange exchange) {
        IdempotencyStore store = exchange.getContext().getRegistry()
                .lookupByNameAndType(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN,
                        IdempotencyStore.class);
        if (store == null) {
            throw new TqlException(CONFLICT, "Idempotency store is not configured");
        }
        return store;
    }

    private static String requestHash(Exchange exchange) {
        String method = exchange.getMessage().getHeader(Exchange.HTTP_METHOD, "", String.class);
        String path = exchange.getMessage().getHeader(Exchange.HTTP_PATH, "", String.class);
        String body = exchange.getMessage().getBody(String.class);
        if (body == null) {
            body = "";
        }
        // Re-set the body so the request binder can read it again after we consumed it.
        exchange.getMessage().setBody(body);
        return sha256(method + "\n" + path + "\n" + body);
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
