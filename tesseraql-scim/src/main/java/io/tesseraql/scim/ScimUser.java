package io.tesseraql.scim;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * The SCIM 2.0 core User resource, restricted to the attributes TesseraQL maps (design ch. 10.15,
 * RFC 7643 §4.1). Unknown attributes are ignored on input and omitted when null on output.
 *
 * @param schemas    the resource's schema URIs (defaults to the core User schema)
 * @param id         the service-provider id (our user id)
 * @param externalId the id assigned by the provisioning client (the IdP)
 * @param userName   the unique login name (required by SCIM)
 * @param name       the user's structured name
 * @param emails     the user's email addresses
 * @param active     whether the account is active
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUser(List<String> schemas, String id, String externalId, String userName,
        Name name, List<Email> emails, Boolean active) {

    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";

    public ScimUser {
        schemas = schemas == null || schemas.isEmpty() ? List.of(SCHEMA) : List.copyOf(schemas);
        emails = emails == null ? List.of() : List.copyOf(emails);
    }

    /** The primary email value, or the first email, or null when none is present. */
    public String primaryEmail() {
        return emails.stream().filter(email -> Boolean.TRUE.equals(email.primary())).findFirst()
                .or(() -> emails.stream().findFirst())
                .map(Email::value)
                .orElse(null);
    }

    /** A structured SCIM name (RFC 7643 §4.1.1). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Name(String givenName, String familyName, String formatted) {
    }

    /** A SCIM multi-valued email (RFC 7643 §4.1.2). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Email(String value, Boolean primary) {
    }
}
