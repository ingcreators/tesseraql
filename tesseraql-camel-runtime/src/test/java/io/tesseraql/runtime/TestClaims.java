package io.tesseraql.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Completes a hand-minted test JWT so it is addressed and expiring
 * (docs/audit-hardening.md Decision 1).
 *
 * <p>Every integration test in this module mints its own bearer token, and none of them carried
 * {@code exp} or {@code aud} — which was fine while the authenticator ignored both. It no longer
 * does: an absent {@code exp} is a token that never expires, and an absent {@code aud} is a token
 * not bound to this application, so both are refusals now.
 *
 * <p>Filling them in here rather than in thirty-odd copies keeps the change to one place and keeps
 * each test's claim map about what that test is testing. A case that means to omit or contradict
 * either passes it explicitly — a null value drops the claim entirely — and its value wins.
 */
final class TestClaims {

    /**
     * Every audience this module's fixtures run under.
     *
     * <p>Most integration tests copy a gallery app and inherit its declared audience — 57 use
     * {@code user-admin-app}, a handful use {@code inventory-app}, {@code juchu-kanri-app} or
     * {@code scaffold-demo-app} — and the rest write a configuration inline. Rather than teach
     * every test which application it happens to be running, the token names all of them: {@code
     * aud} is legitimately an array, and the authenticator matches on any element, so a token
     * addressed to this list is addressed to whichever app the test started.
     *
     * <p>The looseness is a fixture's, not the product's: each app still declares exactly the
     * audience it answers to, and a token naming only some other application is refused. That is
     * what {@code JwtAuthenticatorTest} pins.
     */
    static final java.util.List<String> AUDIENCE = java.util.List.of(
            "https://user-admin.example.com",
            "https://inventory.example.com",
            "https://juchu-kanri.example.com",
            "https://scaffold-demo.example.com",
            "https://purchase-request.example.com",
            "https://procurement.example.com",
            "https://helpdesk.example.com",
            "https://app.example.com");

    /**
     * The audience the fixtures that write their own configuration inline declare, as opposed to
     * the ones that copy a gallery app and inherit its.
     */
    static final String INLINE_FIXTURE = "https://app.example.com";

    private TestClaims() {
    }

    /** The claims, plus {@code exp} and {@code aud} where the caller did not supply them. */
    static Map<String, Object> addressed(Map<String, Object> claims) {
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("exp", System.currentTimeMillis() / 1000L + 3600);
        complete.put("aud", AUDIENCE);
        complete.putAll(claims);
        complete.values().removeIf(java.util.Objects::isNull);
        return complete;
    }
}
