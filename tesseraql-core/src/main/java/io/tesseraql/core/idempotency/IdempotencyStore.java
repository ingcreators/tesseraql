package io.tesseraql.core.idempotency;

/**
 * Stores idempotency records so a repeated request with the same key returns the original result
 * (design ch. 39.4, 39.5). Keyed by {@code (scope, key)} where scope isolates tenant/app/route.
 */
public interface IdempotencyStore {

    /**
     * Begins processing for an idempotency key.
     *
     * <ul>
     *   <li>{@link Proceed} — no prior record; the caller should process and then call
     *       {@link #complete}.</li>
     *   <li>{@link Replay} — a completed record with the same request exists; return it.</li>
     *   <li>{@link Conflict} — a record is in progress, or the same key was used for a different
     *       request.</li>
     * </ul>
     *
     * @param requestHash a stable hash of the request (method, path, body)
     * @param ttlMillis   how long the record remains valid
     */
    BeginResult begin(String scope, String key, String requestHash, long ttlMillis);

    /** Records the final response for a key so later replays can return it. */
    void complete(String scope, String key, int status, String body, String contentType);

    /** Outcome of {@link #begin}. */
    sealed interface BeginResult permits Proceed, Replay, Conflict {
    }

    /** Proceed with processing; no prior record exists. */
    record Proceed() implements BeginResult {
    }

    /** Return the previously stored response. */
    record Replay(int status, String body, String contentType) implements BeginResult {
    }

    /** Reject: in progress, or the key was reused for a different request. */
    record Conflict(String reason) implements BeginResult {
    }
}
