package io.tesseraql.saml;

import java.util.Map;

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
 * @param subject     attribute holding the immutable link subject
 *                    (docs/application-roles.md structural decision 3; falls back to the
 *                    persistent NameID when null)
 * @param attributes  assertion attribute name → store attribute name, re-synced into
 *                    {@code tql_user_attributes} at every linked login; capture is declared,
 *                    not promiscuous — unmapped attributes stay discarded
 */
public record SamlAttributeMapping(String loginId, String displayName, String email, String roles,
        String groups, String tenant, String subject, Map<String, String> attributes) {

    public SamlAttributeMapping {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Without link-subject and attribute-capture mappings (pre-link callers and tests). */
    public SamlAttributeMapping(String loginId, String displayName, String email, String roles,
            String groups, String tenant) {
        this(loginId, displayName, email, roles, groups, tenant, null, Map.of());
    }
}
