package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Declarative HTTP caching for a query route's response (docs/response-shaping.md, "HTTP
 * caching"): a {@code Cache-Control} header and a content {@code ETag} with conditional-GET
 * handling. Query recipes only — a command's response must never come from a cache — and
 * {@code public} visibility lints onto {@code auth: public} routes only, because an
 * authenticated response is per-principal by definition.
 *
 * @param maxAge               how long a client may reuse the response (duration string)
 * @param visibility           {@code private} (default) or {@code public}
 * @param etag                 hash the rendered body and answer {@code If-None-Match} with
 *                             {@code 304} (default true)
 * @param staleWhileRevalidate optional stale-while-revalidate window (duration string)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CacheSpec(String maxAge, String visibility, Boolean etag,
        String staleWhileRevalidate) {

    /** Whether conditional-GET ETags are on (the default). */
    public boolean etagEnabled() {
        return !Boolean.FALSE.equals(etag);
    }

    /** The Cache-Control visibility, defaulting to {@code private}. */
    public String effectiveVisibility() {
        return visibility == null || visibility.isBlank() ? "private" : visibility;
    }
}
