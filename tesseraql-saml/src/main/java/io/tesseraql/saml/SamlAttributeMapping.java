package io.tesseraql.saml;

/**
 * Maps SAML assertion attributes onto principal fields (design ch. 10.14). Each value is the SAML
 * {@code Attribute} {@code Name} to read; any of them may be null, in which case that field is left
 * empty. When {@code loginId} is null the subject NameID is used as the login id.
 *
 * @param loginId     attribute holding the login id (falls back to the NameID when null)
 * @param displayName attribute holding the display name
 * @param email       attribute holding the email address (used when provisioning a local user)
 * @param roles       attribute holding role values (may be multi-valued)
 * @param groups      attribute holding group values (may be multi-valued)
 * @param tenant      attribute holding the tenant id
 */
public record SamlAttributeMapping(String loginId, String displayName, String email, String roles,
        String groups, String tenant) {
}
