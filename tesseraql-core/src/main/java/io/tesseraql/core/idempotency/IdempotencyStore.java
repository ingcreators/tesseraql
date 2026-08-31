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

    /**
     * Records the final response for a key so later replays can return it. {@code headers}
     * carries the allowlisted response headers a replay must re-emit - the {@code HX-Trigger}
     * toast, the PRG {@code Location} (docs/idempotency-key.md decision 6); empty when the
     * response set none of them.
     */
    void complete(String scope, String key, int status, String body, String contentType,
            java.util.Map<String, String> headers);

    /**
     * Releases a claim that will never complete: the request failed before a commit, so the key
     * must stay spendable (docs/idempotency-key.md decision 1). Removes the record only while it
     * is still in progress - a completed record is a stored response and stays.
     */
    void release(String scope, String key);

    /** Outcome of {@link #begin}. */
    sealed interface BeginResult permits Proceed, Replay, Conflict {
    }

    /** Proceed with processing; no prior record exists. */
    record Proceed() implements BeginResult {
    }

    /** Return the previously stored response, its allowlisted headers included. */
    record Replay(int status, String body, String contentType,
            java.util.Map<String, String> headers) implements BeginResult {
    }

    /**
     * Reject. {@code inFlight} distinguishes the race (the first request is still running,
     * 409) from the reuse (same key, different request - a stale tab or a bug, 422).
     */
    record Conflict(String reason, boolean inFlight) implements BeginResult {
    }
}
