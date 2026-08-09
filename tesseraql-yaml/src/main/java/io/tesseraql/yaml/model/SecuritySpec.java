package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Route security declaration (design ch. 6.3, 11). Routes are deny-by-default; a route is only
 * public when explicitly declared so (design ch. 20.14).
 *
 * @param auth     authentication type: {@code bearer}, {@code browser}, {@code api-key}, etc.
 * @param policy   authorization policy id evaluated against the principal
 * @param csrf     CSRF posture: {@code auto} (default; browser state-changing routes are
 *                 protected), {@code required}, or {@code off} (docs/vocabulary-cleanup.md
 *                 slice 1 — one enum on routes and defaults rules alike)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SecuritySpec(String auth, String policy, String csrf) {

    /**
     * Whether the CSRF validator wires for this route: {@code required} always, {@code off}
     * never, {@code auto} for a browser-authenticated non-GET. An <em>absent</em> {@code csrf:}
     * is {@code auto} (the documented default, "browser state-changing routes are protected") —
     * not {@code off}; reading absent as off left a browser POST with no CSRF validator when no
     * {@code security.defaults} rule supplied one. The one resolution point for the enum
     * (docs/vocabulary-cleanup.md slice 1).
     */
    public boolean csrfEnforced(String httpMethod) {
        if ("off".equals(csrf)) {
            return false;
        }
        if ("required".equals(csrf)) {
            return true;
        }
        // auto (explicit or absent): protect browser state-changing routes.
        return "browser".equals(auth) && httpMethod != null
                && !"GET".equalsIgnoreCase(httpMethod);
    }
}
