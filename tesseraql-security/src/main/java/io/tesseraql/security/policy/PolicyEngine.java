package io.tesseraql.security.policy;

import io.tesseraql.core.error.TqlDomain;
import io.tesseraql.core.error.TqlErrorCode;
import io.tesseraql.core.error.TqlException;
import io.tesseraql.security.Principal;
import io.tesseraql.security.SecurityConfig;

/**
 * Evaluates authorization policies against a principal, deny-by-default (design ch. 10.9, 20.14).
 */
public final class PolicyEngine {

    /** TQL-SEC-4011: authentication required / failed. */
    public static final TqlErrorCode UNAUTHORIZED = new TqlErrorCode(TqlDomain.SEC, 4011);
    /** TQL-SEC-4031: forbidden by policy. */
    public static final TqlErrorCode FORBIDDEN = new TqlErrorCode(TqlDomain.SEC, 4031);
    /** TQL-SEC-4032: CSRF token missing or invalid. */
    public static final TqlErrorCode CSRF_FAILED = new TqlErrorCode(TqlDomain.SEC, 4032);

    private final SecurityConfig config;

    public PolicyEngine(SecurityConfig config) {
        this.config = config;
    }

    /**
     * Authorizes the principal against the policy, throwing on failure.
     *
     * @throws TqlException {@link #UNAUTHORIZED} if there is no principal, or {@link #FORBIDDEN} if
     *                      the policy is undefined or not satisfied
     */
    public void authorize(String policyId, Principal principal) {
        if (principal == null) {
            throw new TqlException(UNAUTHORIZED, "Authentication required for policy " + policyId);
        }
        Policy policy = config.policy(policyId).orElseThrow(() -> new TqlException(
                FORBIDDEN, "Policy '" + policyId + "' is not defined (deny by default)"));
        if (!policy.permits(principal)) {
            throw new TqlException(FORBIDDEN, "Principal does not satisfy policy " + policyId);
        }
    }

    public boolean permits(String policyId, Principal principal) {
        return principal != null && config.policy(policyId).map(p -> p.permits(principal)).orElse(false);
    }
}
