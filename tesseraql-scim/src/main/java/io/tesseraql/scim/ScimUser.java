package io.tesseraql.scim;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The SCIM 2.0 core User resource, restricted to the attributes TesseraQL maps (design ch. 10.15,
 * RFC 7643 §4.1), plus the enterprise extension's org attributes (RFC 7643 §4.3) — provisioning is
 * the natural attribute source, so they are no longer parsed away (docs/application-roles.md
 * structural decision 3). Unknown attributes are ignored on input and omitted when null on output.
 *
 * @param schemas    the resource's schema URIs (defaults to the core User schema)
 * @param id         the service-provider id (our user id)
 * @param externalId the id assigned by the provisioning client (the IdP)
 * @param userName   the unique login name (required by SCIM)
 * @param name       the user's structured name
 * @param emails     the user's email addresses
 * @param active     whether the account is active
 * @param enterprise the enterprise-extension attributes, or null when the client sent none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScimUser(List<String> schemas, String id, String externalId, String userName,
        Name name, List<Email> emails, Boolean active,
        @JsonProperty(ScimUser.ENTERPRISE_SCHEMA) Enterprise enterprise) {

    public static final String SCHEMA = "urn:ietf:params:scim:schemas:core:2.0:User";
    public static final String ENTERPRISE_SCHEMA = "urn:ietf:params:scim:schemas:extension:enterprise:2.0:User";

    public ScimUser {
        schemas = schemas == null || schemas.isEmpty() ? List.of(SCHEMA) : List.copyOf(schemas);
        emails = emails == null ? List.of() : List.copyOf(emails);
    }

    /** Without the enterprise extension (pre-extension callers and tests). */
    public ScimUser(List<String> schemas, String id, String externalId, String userName,
            Name name, List<Email> emails, Boolean active) {
        this(schemas, id, externalId, userName, name, emails, active, null);
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

    /** The enterprise User extension's org attributes (RFC 7643 §4.3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Enterprise(String department, String division, String costCenter,
            String employeeNumber, Manager manager) {

        /** The manager's value member ({@code manager.value}, the manager's user id). */
        public String managerValue() {
            return manager == null ? null : manager.value();
        }
    }

    /** The enterprise extension's complex {@code manager} attribute (RFC 7643 §4.3). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Manager(String value, String displayName) {
    }
}
