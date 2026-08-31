package io.tesseraql.compiler.binding;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.core.idempotency.IdempotencyStore;
import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.Headers;
import io.tesseraql.pipeline.Step;
import io.tesseraql.pipeline.TesseraqlProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pipeline steps implementing request idempotency (design ch. 39.4, 39.5).
 *
 * <p>{@link Begin} runs before request binding: on a replay it sets the stored response and marks
 * the exchange so the route short-circuits; on a conflict it raises {@code TQL-IDEM-4090} (409).
 * {@link Complete} runs after the response is rendered and persists it for future replays.
 */
public final class IdempotencyProcessors {

    /** Exchange property set to {@code true} when the response is a stored replay. */
    public static final String REPLAY_PROPERTY = "TqlIdemReplay";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String KEY_FIELD = "_idempotency";
    /**
     * The headers a replay re-emits (docs/idempotency-key.md decision 6): the htmx signals and
     * the PRG Location. Everything else - security headers, cache control - is the fresh
     * response's business, applied by the edge as always.
     */
    private static final java.util.List<String> REPLAYED_HEADERS = java.util.List.of(
            "HX-Trigger", "HX-Redirect", "HX-Retarget", "HX-Reswap", "Location");
    private static final TqlErrorCode CONFLICT = new TqlErrorCode(TqlDomain.IDEM, 4090);
    /** Same key, different request: a stale tab or a bug, not a retry (422, not 409). */
    private static final TqlErrorCode MISMATCH = new TqlErrorCode(TqlDomain.IDEM, 4221);
    private static final TqlErrorCode KEY_REQUIRED = new TqlErrorCode(TqlDomain.FIELD, 2007);

    private IdempotencyProcessors() {
    }

    /** Begins idempotent processing before request binding. */
    public static Step begin(String scope, long ttlMillis, boolean required) {
        // Named rather than a lambda: begin and complete have to be paired, and the
        // recipe-governance matrix test can only check that by reading the compiled route back.
        return new IdempotencyBegin(scope, ttlMillis, required);
    }

    /** @see #begin(String, long, boolean) */
    public record IdempotencyBegin(String scope, long ttlMillis, boolean required)
            implements
                Step {

        @Override
        public void process(io.tesseraql.pipeline.Exchange exchange) {
            String key = keyOf(exchange);
            if (key == null) {
                if (required) {
                    throw new TqlException(KEY_REQUIRED, "Missing required " + KEY_HEADER
                            + " header or " + KEY_FIELD + " form field");
                }
                return; // idempotency optional and not requested
            }
            IdempotencyStore store = store(exchange);
            String hash = requestHash(exchange);
            IdempotencyStore.BeginResult result = store.begin(scope, key, hash, ttlMillis);
            switch (result) {
                case IdempotencyStore.Proceed _ ->
                    // First time: the claim is recorded on the exchange so the runner can
                    // release it if the request fails before Complete stores a response -
                    // the key is spent by the commit, not the attempt
                    // (docs/idempotency-key.md decision 1).
                    exchange.setProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM,
                            scope + "\n" + key);
                case IdempotencyStore.Replay replay -> {
                    exchange.setProperty(REPLAY_PROPERTY, true);
                    exchange.setBody(replay.body());
                    exchange.response().status(replay.status());
                    if (replay.contentType() != null) {
                        exchange.response().header(Headers.CONTENT_TYPE, replay.contentType());
                    }
                    // The stored HX-Trigger toast and the PRG Location replay too - the user
                    // who double-clicked still sees "Order placed" and still lands on the
                    // receipt page (docs/idempotency-key.md decision 6).
                    replay.headers().forEach((name, value) -> exchange.response()
                            .header(name, value));
                }
                case IdempotencyStore.Conflict conflict ->
                    throw new TqlException(conflict.inFlight() ? CONFLICT : MISMATCH,
                            conflict.reason());
            }
        }
    }

    /** Persists the rendered response so future requests with the same key replay it. */
    public static Step complete(String scope) {
        return new IdempotencyComplete(scope);
    }

    /** @see #complete(String) */
    public record IdempotencyComplete(String scope) implements Step {

        @Override
        public void process(io.tesseraql.pipeline.Exchange exchange) {
            if (Boolean.TRUE.equals(exchange.getProperty(REPLAY_PROPERTY))) {
                return;
            }
            String key = keyOf(exchange);
            if (key == null) {
                return;
            }
            int status = exchange.response().statusOr200();
            String body = exchange.getBody(String.class);
            String contentType = exchange.response().header(Headers.CONTENT_TYPE);
            java.util.Map<String, String> replayed = new java.util.LinkedHashMap<>();
            for (String name : REPLAYED_HEADERS) {
                String value = exchange.response().header(name);
                if (value != null) {
                    replayed.put(name, value);
                }
            }
            store(exchange).complete(scope, key, status, body, contentType, replayed);
            exchange.setProperty(TesseraqlProperties.IDEMPOTENCY_CLAIM, null);
        }
    }

    /**
     * The declared key: the {@code Idempotency-Key} header first, else the {@code _idempotency}
     * hidden field a rendered form echoes - header-then-field, the same precedence the CSRF
     * check uses (docs/idempotency-key.md decision 5). Null when neither transport carries one.
     */
    private static String keyOf(Exchange exchange) {
        String key = exchange.request().header(KEY_HEADER);
        if (key == null || key.isBlank()) {
            key = exchange.request().param(KEY_FIELD);
        }
        return key == null || key.isBlank() ? null : key;
    }

    private static IdempotencyStore store(Exchange exchange) {
        IdempotencyStore store = exchange.beans().lookup(TesseraqlProperties.IDEMPOTENCY_STORE_BEAN,
                IdempotencyStore.class);
        if (store == null) {
            throw new TqlException(CONFLICT, "Idempotency store is not configured");
        }
        return store;
    }

    private static String requestHash(Exchange exchange) {
        String method = exchange.request().method() == null ? "" : exchange.request().method();
        String path = exchange.request().path() == null ? "" : exchange.request().path();
        String body = exchange.getBody(String.class);
        if (body == null) {
            body = "";
        }
        // Re-set the body so the request binder can read it again after we consumed it.
        exchange.setBody(body);
        String payload = body.isEmpty() ? formPayload(exchange) : body;
        return sha256(principalKey(exchange) + "\n" + method + "\n" + path + "\n" + payload);
    }

    /**
     * The canonical form payload: a browser form's fields never reach the exchange body (the
     * edge parses them into {@code formFields()}), so hashing the body alone made every form
     * post of a route look identical. Sorted {@code name=value} lines, reserved fields
     * excluded - {@code _csrf} varies by session, {@code _idempotency} is the key itself
     * (docs/idempotency-key.md decision 2), and {@code _return} varies by the page the user
     * came from without changing what the command does.
     */
    private static String formPayload(Exchange exchange) {
        var fields = exchange.request().formFields();
        if (fields.isEmpty()) {
            return "";
        }
        StringBuilder canonical = new StringBuilder();
        fields.entrySet().stream()
                .filter(field -> !"_csrf".equals(field.getKey())
                        && !"_idempotency".equals(field.getKey())
                        && !"_return".equals(field.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(field -> field.getValue().forEach(
                        value -> canonical.append(field.getKey()).append('=').append(value)
                                .append('\n')));
        return canonical.toString();
    }

    /**
     * The authenticated principal, folded into every hash: the recipe's scope is per user, and
     * folding the user in gets that without a schema change - another user replaying a stolen
     * key mismatches and is refused (docs/idempotency-key.md decision 2).
     */
    private static String principalKey(Exchange exchange) {
        io.tesseraql.security.Principal principal = exchange.getProperty(
                TesseraqlProperties.PRINCIPAL, io.tesseraql.security.Principal.class);
        if (principal == null) {
            return "";
        }
        String tenant = principal.tenantId() == null ? "" : principal.tenantId();
        String subject = principal.subject() == null ? "" : principal.subject();
        return tenant + ":" + subject;
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
