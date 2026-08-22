package io.tesseraql.pipeline.auth;

import io.tesseraql.pipeline.Exchange;
import io.tesseraql.pipeline.TesseraqlProperties;
import io.tesseraql.security.net.SignInAllowList;
import io.tesseraql.security.session.SessionStore;

/**
 * Where a session may be established from (docs/access-governance.md structural decision 8,
 * layer A).
 *
 * <p>Three routes create sessions — the password login, the OIDC callback and the SAML assertion
 * consumer — and all three already resolved the caller's client facts the same way before
 * calling {@code SessionStore.create}. The allow-list check belongs at exactly those three
 * moments, and a list of three places to remember is a list somebody eventually forgets. So the
 * check is on the way to the value they cannot proceed without: asking for the {@link
 * SessionStore.ClientInfo} is what admits the caller, and a refusal happens before there is a
 * session to refuse.
 */
public final class SignInAdmission {

    private SignInAdmission() {
    }

    /**
     * The client facts to record on the new session, once this deployment has admitted the
     * address they were read from.
     *
     * @throws io.tesseraql.core.error.TqlException TQL-SEC-4149 when the deployment names its
     *         sign-in networks and this address is not one of them
     */
    public static SessionStore.ClientInfo admitted(Exchange exchange) {
        SessionStore.ClientInfo client = SessionStore.ClientInfo.of(
                exchange.getMessage().getHeader("User-Agent", String.class),
                exchange.getMessage().getHeader("X-Forwarded-For", String.class),
                exchange.getMessage().getHeader(
                        io.tesseraql.pipeline.Headers.REMOTE_ADDRESS,
                        String.class));
        allowList(exchange).admit(client.remoteAddr());
        return client;
    }

    /** The configured list, or the unrestricted one when the deployment named no network. */
    private static SignInAllowList allowList(Exchange exchange) {
        SignInAllowList configured = exchange.beans().lookup(
                TesseraqlProperties.SIGN_IN_ALLOW_LIST_BEAN, SignInAllowList.class);
        return configured == null ? SignInAllowList.EVERYWHERE : configured;
    }
}
