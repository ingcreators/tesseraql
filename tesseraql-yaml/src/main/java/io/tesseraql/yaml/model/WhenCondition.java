package io.tesseraql.yaml.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The {@code when:} condition of a scope {@link MatchArm} (roadmap Phase 29 — organizational data
 * scoping). It mirrors an authorization-policy rule: the arm matches when the principal has the
 * named {@code role} or {@code permission}, or carries the named {@code claim} equal to
 * {@code equals}. Exactly one of {@code role}/{@code permission}/{@code claim} is set; matching is
 * deny-by-default, evaluated by the compiler against the same principal route policies use.
 *
 * <p>The 2-way expression language is deliberately not used here — it has no role-membership or
 * function-call syntax — so role/permission/claim matching is structural, like {@code policy:}.
 *
 * @param role       required role
 * @param permission required permission
 * @param claim      required claim name (paired with {@code equals})
 * @param value      required claim value (the {@code equals:} key)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WhenCondition(String role, String permission, String claim,
        @JsonProperty("equals") String value) {
}
