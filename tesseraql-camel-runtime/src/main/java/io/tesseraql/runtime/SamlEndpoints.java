package io.tesseraql.runtime;

/**
 * SAML SP/IdP endpoint coordinates for SP-initiated SSO and single logout (design ch. 10.14). The IdP
 * URLs are nullable: the login route is mounted only when {@code idpSsoUrl} is set, and a logout
 * redirects to the IdP only when {@code idpSloUrl} is set.
 *
 * @param spEntityId the SP entity id (issuer)
 * @param acsUrl     the SP Assertion Consumer Service URL
 * @param idpSsoUrl  the IdP single sign-on (HTTP-Redirect) endpoint, or null
 * @param idpSloUrl  the IdP single logout (HTTP-Redirect) endpoint, or null
 */
record SamlEndpoints(String spEntityId, String acsUrl, String idpSsoUrl, String idpSloUrl) {
}
